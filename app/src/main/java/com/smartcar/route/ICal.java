/*
 * Copyright © 2026 smart-car-software contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.smartcar.route;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of iCalendar (RFC 5545) a route needs: VEVENTs with their time, title, place, and
 * the occurrences of a recurring one within a window. Servers are asked to expand recurrences
 * themselves (CalDAV {@code expand}); when one does not, the common RRULEs are expanded here —
 * DAILY, WEEKLY (BYDAY), MONTHLY (BYMONTHDAY or «2TU»/«-1FR»), YEARLY, with INTERVAL, COUNT,
 * UNTIL, EXDATE and moved occurrences (RECURRENCE-ID). Pure.
 */
final class ICal {
    static final class VEvent {
        String uid = "";
        String summary = "";
        String location = "";
        String status = "";
        String transp = "";
        long startMs;
        long endMs;
        boolean allDay;
        ZoneId zone = ZoneOffset.UTC;
        LocalDateTime localStart;
        Duration duration = Duration.ZERO;
        String rrule = "";
        final Set<Long> exdates = new HashSet<>();
        long recurrenceId;
        double lat = Double.NaN;
        double lon = Double.NaN;
    }

    /** One occurrence. */
    static final class Occurrence {
        final VEvent event;
        final long startMs;
        final long endMs;

        Occurrence(VEvent event, long startMs, long endMs) {
            this.event = event;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final Pattern GEO_URI = Pattern.compile("geo:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int MAX_STEPS = 5000;

    private ICal() {
    }

    /** Lines unfolded (a line starting with a space or tab continues the previous one). */
    static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else if (!raw.isEmpty()) {
                out.add(raw);
            }
        }
        return out;
    }

    static List<VEvent> parse(String text, ZoneId local) {
        List<VEvent> out = new ArrayList<>();
        VEvent e = null;
        int depth = 0; // VALARM and the like inside a VEVENT
        String dtend = null, dtendParams = null, durationText = null;
        for (String line : unfold(text)) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                e = new VEvent();
                depth = 0;
                dtend = dtendParams = durationText = null;
                continue;
            }
            if (e == null) continue;
            if (line.regionMatches(true, 0, "BEGIN:", 0, 6)) {
                depth++;
                continue;
            }
            if (line.regionMatches(true, 0, "END:", 0, 4)) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                if (line.equalsIgnoreCase("END:VEVENT")) {
                    if (e.localStart != null) {
                        finish(e, dtend, dtendParams, durationText, local);
                        out.add(e);
                    }
                    e = null;
                }
                continue;
            }
            if (depth > 0) continue;
            int colon = valueColon(line);
            if (colon < 0) continue;
            String head = line.substring(0, colon), value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String name = (semi < 0 ? head : head.substring(0, semi)).toUpperCase(Locale.ROOT);
            String params = semi < 0 ? "" : head.substring(semi + 1);
            switch (name) {
                case "UID": e.uid = value.trim(); break;
                case "SUMMARY": e.summary = unescape(value).trim(); break;
                case "LOCATION": e.location = unescape(value).trim(); break;
                case "STATUS": e.status = value.trim().toUpperCase(Locale.ROOT); break;
                case "TRANSP": e.transp = value.trim().toUpperCase(Locale.ROOT); break;
                case "RRULE": e.rrule = value.trim(); break;
                case "DTSTART": {
                    e.allDay = isDate(params, value);
                    e.zone = zoneOf(params, value, local);
                    e.localStart = local(value);
                    break;
                }
                case "DTEND": dtend = value; dtendParams = params; break;
                case "DURATION": durationText = value.trim(); break;
                case "EXDATE":
                    for (String v : value.split(",")) {
                        LocalDateTime t = local(v);
                        if (t != null) e.exdates.add(t.atZone(zoneOf(params, v, local)).toInstant().toEpochMilli());
                    }
                    break;
                case "RECURRENCE-ID": {
                    LocalDateTime t = local(value);
                    if (t != null) e.recurrenceId = t.atZone(zoneOf(params, value, local)).toInstant().toEpochMilli();
                    break;
                }
                case "GEO": {
                    String[] p = value.split("[;,]");
                    if (p.length == 2) setGeo(e, p[0], p[1]);
                    break;
                }
                case "X-APPLE-STRUCTURED-LOCATION": {
                    Matcher m = GEO_URI.matcher(value);
                    if (m.find() && !Geo.valid(e.lat, e.lon)) setGeo(e, m.group(1), m.group(2));
                    break;
                }
                default:
                    break;
            }
        }
        return out;
    }

    /** The colon that ends the property's name and parameters (not one inside a quoted parameter). */
    private static int valueColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static void setGeo(VEvent e, String lat, String lon) {
        try {
            double a = Double.parseDouble(lat.trim()), o = Double.parseDouble(lon.trim());
            if (Geo.valid(a, o)) {
                e.lat = a;
                e.lon = o;
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static void finish(VEvent e, String dtend, String dtendParams, String durationText,
                               ZoneId local) {
        ZonedDateTime start = e.localStart.atZone(e.zone);
        e.startMs = start.toInstant().toEpochMilli();
        long end = e.startMs;
        if (dtend != null && local(dtend) != null) {
            end = local(dtend).atZone(zoneOf(dtendParams, dtend, local)).toInstant().toEpochMilli();
        } else if (durationText != null) {
            Duration d = duration(durationText);
            if (d != null) end = start.plus(d).toInstant().toEpochMilli();
        } else if (e.allDay) {
            end = start.plusDays(1).toInstant().toEpochMilli();
        }
        if (end < e.startMs) end = e.startMs;
        e.endMs = end;
        e.duration = Duration.ofMillis(end - e.startMs);
    }

    static Duration duration(String s) {
        s = s.trim().toUpperCase(Locale.ROOT);
        boolean neg = s.startsWith("-");
        if (neg || s.startsWith("+")) s = s.substring(1);
        try {
            Duration d;
            Matcher w = Pattern.compile("^P(\\d+)W$").matcher(s);
            if (w.find()) d = Duration.ofDays(7L * Long.parseLong(w.group(1)));
            else d = Duration.parse(s);
            return neg ? d.negated() : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isDate(String params, String value) {
        return params.toUpperCase(Locale.ROOT).contains("VALUE=DATE") && !params.toUpperCase(Locale.ROOT).contains("DATE-TIME")
                || value.trim().length() == 8;
    }

    private static ZoneId zoneOf(String params, String value, ZoneId local) {
        if (value.trim().endsWith("Z")) return ZoneOffset.UTC;
        if (params != null) {
            for (String p : params.split(";")) {
                if (p.regionMatches(true, 0, "TZID=", 0, 5)) return zone(p.substring(5).replace("\"", ""), local);
            }
        }
        return local;
    }

    /** An Olson name, the common Windows names, else the device's own zone. */
    static ZoneId zone(String tzid, ZoneId local) {
        String t = tzid.trim();
        try {
            return ZoneId.of(t);
        } catch (DateTimeException ignored) {
        }
        Map<String, String> win = new HashMap<>();
        win.put("russian standard time", "Europe/Moscow");
        win.put("russia time zone 3", "Europe/Samara");
        win.put("ekaterinburg standard time", "Asia/Yekaterinburg");
        win.put("utc", "UTC");
        win.put("gmt standard time", "Europe/London");
        win.put("w. europe standard time", "Europe/Berlin");
        String m = win.get(t.toLowerCase(Locale.ROOT));
        if (m != null) return ZoneId.of(m);
        // «/mozilla.org/…/Europe/Moscow» and the like
        int i = t.lastIndexOf('/');
        if (i > 0) {
            int j = t.lastIndexOf('/', i - 1);
            try {
                return ZoneId.of(t.substring(j + 1));
            } catch (DateTimeException ignored) {
            }
        }
        return local;
    }

    static LocalDateTime local(String v) {
        v = v.trim();
        if (v.endsWith("Z")) v = v.substring(0, v.length() - 1);
        try {
            if (v.length() == 8) return LocalDate.parse(v, D).atStartOfDay();
            return LocalDateTime.parse(v.length() > 15 ? v.substring(0, 15) : v, DT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String unescape(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? ' ' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ occurrences

    /**
     * The occurrences of all these events overlapping [fromMs, toMs): a master's own (moved ones
     * replaced by their RECURRENCE-ID copies), and the rest as they are.
     */
    static List<Occurrence> occurrences(List<VEvent> events, long fromMs, long toMs) {
        Map<String, Set<Long>> moved = new HashMap<>();
        for (VEvent e : events) {
            if (e.recurrenceId != 0) moved.computeIfAbsent(e.uid, k -> new HashSet<>()).add(e.recurrenceId);
        }
        List<Occurrence> out = new ArrayList<>();
        for (VEvent e : events) {
            if (e.rrule.isEmpty() || e.recurrenceId != 0) {
                if (e.startMs < toMs && e.endMs > fromMs) out.add(new Occurrence(e, e.startMs, e.endMs));
                continue;
            }
            Set<Long> skip = moved.getOrDefault(e.uid, new HashSet<>());
            for (long s : starts(e, fromMs - e.duration.toMillis(), toMs)) {
                if (e.exdates.contains(s) || skip.contains(s)) continue;
                long end = s + e.duration.toMillis();
                if (s < toMs && end > fromMs) out.add(new Occurrence(e, s, end));
            }
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }

    /** Starts of a recurring event from its DTSTART on, those at or after fromMs, before toMs. */
    static List<Long> starts(VEvent e, long fromMs, long toMs) {
        Map<String, String> r = new HashMap<>();
        for (String part : e.rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).toUpperCase(Locale.ROOT), part.substring(eq + 1).toUpperCase(Locale.ROOT));
        }
        String freq = r.getOrDefault("FREQ", "");
        int interval = Math.max(1, parseInt(r.get("INTERVAL"), 1));
        int count = parseInt(r.get("COUNT"), Integer.MAX_VALUE);
        long until = Long.MAX_VALUE;
        if (r.containsKey("UNTIL")) {
            LocalDateTime u = local(r.get("UNTIL"));
            if (u != null) {
                until = (r.get("UNTIL").endsWith("Z") ? u.atZone(ZoneOffset.UTC) : u.atZone(e.zone))
                        .toInstant().toEpochMilli();
                if (r.get("UNTIL").length() == 8) until += 24 * 3600_000L - 1;
            }
        }
        List<Long> out = new ArrayList<>();
        LocalDateTime start = e.localStart;
        LocalTime time = start.toLocalTime();
        int n = 0;
        int first = 0;
        if (count == Integer.MAX_VALUE) {
            // No COUNT: nothing before the window matters, start a period or two before it.
            LocalDateTime from = java.time.Instant.ofEpochMilli(fromMs).atZone(e.zone).toLocalDateTime();
            long gap;
            switch (freq) {
                case "DAILY": gap = ChronoUnit.DAYS.between(start, from); break;
                case "WEEKLY": gap = ChronoUnit.WEEKS.between(start, from); break;
                case "MONTHLY": gap = ChronoUnit.MONTHS.between(start, from); break;
                case "YEARLY": gap = ChronoUnit.YEARS.between(start, from); break;
                default: gap = 0;
            }
            first = (int) Math.max(0, Math.min(Integer.MAX_VALUE / 2, gap / interval - 1));
        }
        for (int period = first; period < first + MAX_STEPS; period++) {
            List<LocalDateTime> cands = new ArrayList<>();
            switch (freq) {
                case "DAILY":
                    cands.add(start.plusDays((long) period * interval));
                    break;
                case "WEEKLY": {
                    LocalDate weekStart = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .plusWeeks((long) period * interval);
                    List<DayOfWeek> days = new ArrayList<>();
                    for (String d : r.getOrDefault("BYDAY", "").split(",")) {
                        DayOfWeek w = day(d.replaceAll("[-+\\d]", ""));
                        if (w != null) days.add(w);
                    }
                    if (days.isEmpty()) days.add(start.getDayOfWeek());
                    days.sort(null);
                    for (DayOfWeek w : days) cands.add(weekStart.plusDays(w.getValue() - 1).atTime(time));
                    break;
                }
                case "MONTHLY": {
                    LocalDate month = start.toLocalDate().withDayOfMonth(1).plusMonths((long) period * interval);
                    cands.addAll(monthDays(month, r, start.getDayOfMonth(), time));
                    break;
                }
                case "YEARLY": {
                    LocalDate d = start.toLocalDate();
                    int y = d.getYear() + period * interval;
                    if (d.getMonthValue() == 2 && d.getDayOfMonth() == 29 && !java.time.Year.isLeap(y)) break;
                    cands.add(LocalDate.of(y, d.getMonthValue(), d.getDayOfMonth()).atTime(time));
                    break;
                }
                default:
                    if (period == 0) cands.add(start);
                    break;
            }
            for (LocalDateTime c : cands) {
                if (c.isBefore(start)) continue;
                long ms = c.atZone(e.zone).toInstant().toEpochMilli();
                if (ms > until || n >= count) return out;
                n++;
                if (ms >= toMs) return out;
                if (ms >= fromMs) out.add(ms);
            }
            if (!"DAILY WEEKLY MONTHLY YEARLY".contains(freq) || freq.isEmpty()) return out;
        }
        return out;
    }

    private static List<LocalDateTime> monthDays(LocalDate month, Map<String, String> r, int dtDay,
                                                 LocalTime time) {
        List<LocalDateTime> out = new ArrayList<>();
        int len = month.lengthOfMonth();
        if (r.containsKey("BYMONTHDAY")) {
            for (String s : r.get("BYMONTHDAY").split(",")) {
                int d = parseInt(s, 0);
                if (d < 0) d = len + 1 + d;
                if (d >= 1 && d <= len) out.add(month.withDayOfMonth(d).atTime(time));
            }
        } else if (r.containsKey("BYDAY")) {
            for (String s : r.get("BYDAY").split(",")) {
                Matcher m = Pattern.compile("^([-+]?\\d+)?([A-Z]{2})$").matcher(s.trim());
                if (!m.find()) continue;
                DayOfWeek w = day(m.group(2));
                if (w == null) continue;
                if (m.group(1) == null) {
                    for (LocalDate d = month.with(TemporalAdjusters.firstInMonth(w)); d.getMonth() == month.getMonth(); d = d.plusWeeks(1)) {
                        out.add(d.atTime(time));
                    }
                } else {
                    int k = Integer.parseInt(m.group(1).replace("+", ""));
                    LocalDate d = k > 0 ? month.with(TemporalAdjusters.dayOfWeekInMonth(k, w))
                            : month.with(TemporalAdjusters.lastInMonth(w)).minusWeeks(-k - 1);
                    if (d.getMonth() == month.getMonth()) out.add(d.atTime(time));
                }
            }
        } else if (dtDay <= len) {
            out.add(month.withDayOfMonth(dtDay).atTime(time));
        }
        out.sort(null);
        return out;
    }

    private static DayOfWeek day(String s) {
        switch (s) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** «20261110T150000Z» for CalDAV time ranges. */
    static String utc(long ms) {
        return LocalDateTime.ofEpochSecond(ms / 1000, 0, ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DT) + "Z";
    }
}
