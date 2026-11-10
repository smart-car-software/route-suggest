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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * «Куда едем?»: the rows offered when the car starts, in this order —
 * <ol>
 *   <li>calendar events going on or starting within the hour;</li>
 *   <li>routines: where the car went on this weekday at about this time (two weeks or more), or
 *       on most days lately at this time;</li>
 *   <li>home or work: «На работу» when standing at home, «Домой» anywhere else — the first row
 *       when there is nothing above;</li>
 *   <li>the most visited places.</li>
 * </ol>
 * Nothing where the car already stands, and one row per place. Pure: everything comes in
 * {@link Input}. The menu on the cluster ({@link #menu}) has the same head and every place after.
 */
final class Suggester {
    /** Closer than this, the car is already there. */
    static final double AT_M = 300;
    /** Trip ends this close are one place. */
    static final double CLUSTER_M = 150;
    /** A bookmark this close names a place. */
    static final double NAME_M = 200;
    /** Two rows this close go to one place: the first is kept. */
    static final double SAME_M = 250;
    static final int MAX_ROWS = 6;

    static final long DAY_MS = 24L * 3600_000L;
    static final long HISTORY_MS = 120 * DAY_MS;
    static final long WEEKLY_LOOKBACK_MS = 10 * 7 * DAY_MS;
    static final int WEEKLY_WINDOW_MIN = 90;
    static final int WEEKLY_MIN_DAYS = 2;
    static final long DAILY_LOOKBACK_MS = 21 * DAY_MS;
    static final int DAILY_WINDOW_MIN = 60;
    static final int DAILY_MIN_DAYS = 3;
    static final int FREQUENT_MIN = 3;
    static final int FREQUENT_ROWS = 3;
    /** A learned home or work needs this many drives ending there. */
    static final int LEARN_MIN = 3;

    private static final String[] BY_DAY = {"понедельникам", "вторникам", "средам", "четвергам",
            "пятницам", "субботам", "воскресеньям"};

    static final class Input {
        long nowMs;
        ZoneId zone = ZoneId.systemDefault();
        /** Where the car stands; NaN when no fix. */
        double hereLat = Double.NaN;
        double hereLon = Double.NaN;
        List<Trip> trips = Collections.emptyList();
        List<CalEvent> events = Collections.emptyList();
        /** Yandex «Дом», «Работа» and bookmarks. */
        List<Place> places = Collections.emptyList();
    }

    /** Trip ends close together: one place the car goes to. */
    static final class Cluster {
        double lat;
        double lon;
        final List<Trip> trips = new ArrayList<>();
        String name;

        void add(Trip t) {
            trips.add(t);
            int n = trips.size();
            lat += (t.endLat - lat) / n;
            lon += (t.endLon - lon) / n;
        }
    }

    private Suggester() {
    }

    static List<Suggestion> suggest(Input in) {
        return build(in, false);
    }

    /**
     * The menu on the cluster's navigation tab: the same rows in the same order — events,
     * routines, home or work — and then every place there is, Yandex bookmarks and places the
     * car goes to often, the most driven to first. No limit of {@link #MAX_ROWS}.
     */
    static List<Suggestion> menu(Input in) {
        return build(in, true);
    }

    static final int MENU_MAX_ROWS = 60;

    /**
     * Whether the card at start is worth showing on its own: only for something the driver did
     * not tell us to look up — a calendar event or a routine. Home, work and the frequent places
     * alone are always there and stay in the menu.
     */
    static boolean worthAsking(List<Suggestion> rows) {
        for (Suggestion s : rows) {
            if (s.kind == Suggestion.Kind.EVENT || s.kind == Suggestion.Kind.ROUTINE) return true;
        }
        return false;
    }

    private static List<Suggestion> build(Input in, boolean menu) {
        boolean located = Geo.valid(in.hereLat, in.hereLon);
        List<Trip> recent = new ArrayList<>();
        for (Trip t : in.trips) {
            if (t.endMs <= in.nowMs && in.nowMs - t.endMs <= HISTORY_MS) recent.add(t);
        }
        List<Cluster> clusters = clusters(recent);
        for (Cluster c : clusters) c.name = name(c, in.places);
        Place home = homeOrLearned(in.places, clusters, in.zone);
        Place work = workOrLearned(in.places, clusters, home, in.zone);

        Rows rows = new Rows(in, menu ? MENU_MAX_ROWS : MAX_ROWS);
        ZonedDateTime now = Instant.ofEpochMilli(in.nowMs).atZone(in.zone);

        List<CalEvent> events = new ArrayList<>(in.events);
        events.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        for (CalEvent e : events) {
            if (e.endMs <= in.nowMs) continue;
            String when = e.startMs <= in.nowMs ? "идёт"
                    : String.format(Locale.ROOT, "%1$tH:%1$tM",
                    Instant.ofEpochMilli(e.startMs).atZone(in.zone));
            // Only a known place's name is remembered for the drive: «Ужин» is not a place.
            String name = e.place;
            if (e.hasGeo()) {
                // Our own geocoding is the middle of the house: the route goes by the text.
                rows.add(Suggestion.Kind.EVENT, clip(e.title, LABEL_WITH_SUFFIX) + " · " + when, name,
                        e.lat, e.lon, e.geocoded ? e.location : "");
            } else if (!e.location.isEmpty()) {
                rows.add(Suggestion.Kind.EVENT, clip(e.title, LABEL_WITH_SUFFIX) + " · " + when, name,
                        Double.NaN, Double.NaN, e.location);
            }
        }

        List<Routine> routines = new ArrayList<>();
        for (Cluster c : clusters) {
            Routine r = routine(c, now);
            if (r != null) routines.add(r);
        }
        routines.sort((a, b) -> Integer.compare(b.score, a.score));
        for (Routine r : routines) {
            String label = r.cluster.name != null ? r.cluster.name : unnamed(r.cluster, in);
            String when = r.weekly ? "по " + BY_DAY[now.getDayOfWeek().getValue() - 1]
                    : "обычно в это время";
            // «Место в 2,5 км» is a label, not a name to remember for the place.
            double[] at = point(r.cluster, in.places);
            rows.add(Suggestion.Kind.ROUTINE, clip(label, LABEL_WITH_SUFFIX) + " · " + when,
                    r.cluster.name == null ? "" : r.cluster.name, at[0], at[1], "");
        }

        if (located && home != null && home.metersTo(in.hereLat, in.hereLon) <= AT_M) {
            if (work != null) {
                rows.add(Suggestion.Kind.WORK, "На работу", work.name, work.lat, work.lon, "");
            }
        } else if (home != null) {
            rows.add(Suggestion.Kind.HOME, "Домой", home.name, home.lat, home.lon, "");
        }

        if (menu) {
            everyPlace(rows, in, clusters, recent, home, work);
            return rows.out;
        }

        List<Cluster> frequent = new ArrayList<>();
        for (Cluster c : clusters) if (c.trips.size() >= FREQUENT_MIN) frequent.add(c);
        frequent.sort((a, b) -> Integer.compare(b.trips.size(), a.trips.size()));
        int added = 0;
        for (Cluster c : frequent) {
            if (added >= FREQUENT_ROWS) break;
            String label = c.name != null ? c.name : unnamed(c, in);
            double[] at = point(c, in.places);
            if (rows.add(Suggestion.Kind.FREQUENT, clip(label, LABEL), c.name == null ? "" : c.name,
                    at[0], at[1], "")) {
                added++;
            }
        }
        return rows.out;
    }

    /** A drive ending this close to the chosen row was a drive there. */
    static final double CHOSEN_M = 400;
    /** A row with no point (Alice found it): the drive that started this soon after is to it. */
    static final long CHOSEN_TEXT_MS = 10 * 60_000L;

    /**
     * The name a drive gets from the row chosen for it, or null: a row with a point — the drive
     * ended within {@link #CHOSEN_M} of it; a row known by its text only (an organisation
     * bookmark, Alice led) — the drive started within {@link #CHOSEN_TEXT_MS} after the choice.
     * A label («Место в 2 км») is no name.
     */
    static String chosenName(Suggestion s, long chosenAt, Trip t) {
        if (s == null || s.name == null || s.name.trim().isEmpty()) return null;
        if (chosenAt < t.startMs - 30 * 60_000L || chosenAt > t.endMs) return null;
        if (s.hasGeo()) {
            return Geo.meters(s.lat, s.lon, t.endLat, t.endLon) <= CHOSEN_M ? s.name : null;
        }
        return !s.query.isEmpty() && t.startMs - chosenAt <= CHOSEN_TEXT_MS ? s.name : null;
    }

    /** «ул. Рихарда Зорге, 66» and «улица Рихарда Зорге 66» are one address. Pure. */
    static String normAddress(String s) {
        String t = EventFilter.norm(s);
        t = (" " + t + " ").replace(" ул ", " улица ").replace(" пр кт ", " проспект ")
                .replace(" просп ", " проспект ").replace(" пр ", " проспект ")
                .replace(" пер ", " переулок ").replace(" ш ", " шоссе ").replace(" д ", " ")
                .replace(" г ", " ");
        return t.trim().replaceAll("\\s+", " ");
    }

    /** Whether a drive's address is the bookmark's: one holds the other, a house number in it. */
    static boolean sameAddress(String place, String drive) {
        String a = normAddress(place), b = normAddress(drive);
        if (a.isEmpty() || b.isEmpty() || !a.matches(".*\\d.*")) return false;
        return a.endsWith(b) || b.endsWith(a) || a.contains(" " + b) || b.contains(" " + a)
                || a.equals(b);
    }

    /** One more place for the menu, with how many drives ended there. */
    private static final class Candidate {
        final Suggestion.Kind kind;
        final String label;
        final String name;
        final double lat;
        final double lon;
        final String query;
        final int visits;

        Candidate(Suggestion.Kind kind, String label, String name, double lat, double lon,
                  String query, int visits) {
            this.kind = kind;
            this.label = label;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.query = query;
            this.visits = visits;
        }
    }

    /**
     * The menu's tail: home, work and the Yandex bookmarks, and the places with
     * {@value #FREQUENT_MIN}+ drives that no bookmark covers — by the drives that ended there
     * (within {@link #SAME_M}), then by name. Bookmarks never driven to come last.
     */
    private static void everyPlace(Rows rows, Input in, List<Cluster> clusters, List<Trip> recent,
                                   Place home, Place work) {
        List<Candidate> all = new ArrayList<>();
        List<Place> known = new ArrayList<>();
        if (home != null) known.add(home);
        if (work != null) known.add(work);
        for (Place p : in.places) {
            if (p.kind == Place.Kind.BOOKMARK && !p.name.trim().isEmpty()) known.add(p);
        }
        Set<Cluster> named = new HashSet<>();
        for (Place p : known) {
            Suggestion.Kind kind = p.kind == Place.Kind.HOME ? Suggestion.Kind.HOME
                    : p.kind == Place.Kind.WORK ? Suggestion.Kind.WORK : Suggestion.Kind.FREQUENT;
            String label = p.kind == Place.Kind.HOME ? "Домой"
                    : p.kind == Place.Kind.WORK ? "На работу" : clip(p.name, LABEL);
            if (p.hasGeo()) {
                int visits = 0;
                for (Trip t : recent) if (p.metersTo(t.endLat, t.endLon) <= SAME_M) visits++;
                all.add(new Candidate(kind, label, p.name, p.lat, p.lon, "", visits));
                continue;
            }
            // No point (an organisation bookmark): the place drives went to under its name (the
            // row chosen, the Navigator's title) gives the visits — and the Navigator's own point
            // for it, if it led there; else Alice finds it by its name and address.
            Cluster same = null;
            for (Cluster c : clusters) {
                if (sameOrganisation(c, p) && (same == null || c.trips.size() > same.trips.size())) {
                    same = c;
                }
            }
            String text = p.address.isEmpty() || p.address.contains(p.name) ? (p.address.isEmpty()
                    ? p.name : p.address) : p.name + ", " + p.address;
            if (same == null) {
                all.add(new Candidate(kind, label, p.name, Double.NaN, Double.NaN, text, 0));
                continue;
            }
            named.add(same);
            boolean navi = false;
            for (Trip t : same.trips) if (t.hasDest()) navi = true;
            double[] at = point(same, in.places);
            all.add(navi ? new Candidate(kind, label, p.name, at[0], at[1], "", same.trips.size())
                    : new Candidate(kind, label, p.name, Double.NaN, Double.NaN, text, same.trips.size()));
        }
        for (Cluster c : clusters) {
            if (c.trips.size() < FREQUENT_MIN || named.contains(c)) continue;
            boolean covered = false;
            for (Place p : known) {
                if (p.metersTo(c.lat, c.lon) <= SAME_M) covered = true;
            }
            if (covered) continue;
            String label = c.name != null ? c.name : unnamed(c, in);
            double[] at = point(c, in.places);
            all.add(new Candidate(Suggestion.Kind.FREQUENT, clip(label, LABEL),
                    c.name == null ? "" : c.name, at[0], at[1], "", c.trips.size()));
        }
        all.sort((a, b) -> a.visits != b.visits ? Integer.compare(b.visits, a.visits)
                : a.label.compareToIgnoreCase(b.label));
        for (Candidate c : all) rows.add(c.kind, c.label, c.name, c.lat, c.lon, c.query);
    }

    /**
     * Drives to an organisation bookmark (no point): named after it (the row chosen, the
     * Navigator's title), or ending at its address — the Navigator keeps a route Alice built as
     * an address, «улица Рихарда Зорге, 66», not the organisation (emulator, mod 30.3.0).
     */
    private static boolean sameOrganisation(Cluster c, Place p) {
        String name = p.name.trim();
        if (c.name != null && c.name.trim().equalsIgnoreCase(name)) return true;
        for (Trip t : c.trips) {
            if (t.name != null && t.name.trim().equalsIgnoreCase(name)) return true;
            if (t.naviName != null && t.naviName.trim().equalsIgnoreCase(name)) return true;
            if (!p.address.isEmpty() && t.address != null && sameAddress(p.address, t.address)) {
                return true;
            }
        }
        return false;
    }

    /** The rows so far: at most {@code max}, none where the car is, one per place. */
    private static final class Rows {
        final Input in;
        final int max;
        final List<Suggestion> out = new ArrayList<>();

        Rows(Input in, int max) {
            this.in = in;
            this.max = max;
        }

        boolean add(Suggestion.Kind kind, String row, String name, double lat, double lon,
                    String query) {
            if (out.size() >= max) return false;
            if (Geo.valid(lat, lon)) {
                if (Geo.valid(in.hereLat, in.hereLon)
                        && Geo.meters(lat, lon, in.hereLat, in.hereLon) <= AT_M) {
                    return false;
                }
                for (Suggestion s : out) {
                    if (s.hasGeo() && Geo.meters(lat, lon, s.lat, s.lon) <= SAME_M) return false;
                }
            } else {
                for (Suggestion s : out) {
                    if (!s.hasGeo() && s.query.equalsIgnoreCase(query)) return false;
                }
            }
            out.add(new Suggestion(kind, row, name, lat, lon, query));
            return true;
        }
    }

    private static final class Routine {
        final Cluster cluster;
        final boolean weekly;
        final int score;

        Routine(Cluster cluster, boolean weekly, int score) {
            this.cluster = cluster;
            this.weekly = weekly;
            this.score = score;
        }
    }

    /**
     * Whether the place is where the car goes about now: left for it on this weekday within
     * ±{@value #WEEKLY_WINDOW_MIN} min on {@value #WEEKLY_MIN_DAYS}+ days of the last ten weeks
     * (weekly), or within ±{@value #DAILY_WINDOW_MIN} min on {@value #DAILY_MIN_DAYS}+ days of
     * the same kind (workday / weekend) of the last three weeks (daily). Today does not count.
     */
    static Routine routine(Cluster c, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        int nowMin = now.getHour() * 60 + now.getMinute();
        boolean weekend = isWeekend(now.getDayOfWeek());
        Set<LocalDate> weekly = new HashSet<>();
        Set<LocalDate> daily = new HashSet<>();
        long nowMs = now.toInstant().toEpochMilli();
        for (Trip t : c.trips) {
            ZonedDateTime s = Instant.ofEpochMilli(t.startMs).atZone(now.getZone());
            LocalDate d = s.toLocalDate();
            if (!d.isBefore(today)) continue;
            int diff = Math.abs(s.getHour() * 60 + s.getMinute() - nowMin);
            if (s.getDayOfWeek() == now.getDayOfWeek() && diff <= WEEKLY_WINDOW_MIN
                    && nowMs - t.startMs <= WEEKLY_LOOKBACK_MS) {
                weekly.add(d);
            }
            if (isWeekend(s.getDayOfWeek()) == weekend && diff <= DAILY_WINDOW_MIN
                    && nowMs - t.startMs <= DAILY_LOOKBACK_MS) {
                daily.add(d);
            }
        }
        if (weekly.size() >= WEEKLY_MIN_DAYS) return new Routine(c, true, weekly.size() * 2);
        if (daily.size() >= DAILY_MIN_DAYS) return new Routine(c, false, daily.size());
        return null;
    }

    /** What fits a row on the cluster (460 px): alone, and before «· по воскресеньям». */
    static final int LABEL = 30;
    static final int LABEL_WITH_SUFFIX = 18;

    static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }

    private static boolean isWeekend(DayOfWeek d) {
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** Greedy, in time order: each end joins the nearest place within {@link #CLUSTER_M}. */
    static List<Cluster> clusters(List<Trip> trips) {
        List<Trip> sorted = new ArrayList<>(trips);
        sorted.sort((a, b) -> Long.compare(a.endMs, b.endMs));
        List<Cluster> out = new ArrayList<>();
        for (Trip t : sorted) {
            if (!Geo.valid(t.endLat, t.endLon)) continue;
            Cluster best = null;
            double bestM = CLUSTER_M;
            for (Cluster c : out) {
                double m = Geo.meters(c.lat, c.lon, t.endLat, t.endLon);
                if (m <= bestM) {
                    best = c;
                    bestM = m;
                }
            }
            if (best == null) {
                best = new Cluster();
                best.lat = t.endLat;
                best.lon = t.endLon;
                out.add(best);
                best.trips.add(t);
            } else {
                best.add(t);
            }
        }
        return out;
    }

    /**
     * A place's name, by who knows it best: the row the driver chose for drives there, then the
     * nearest Yandex place within {@link #NAME_M} («Дом», «Работа», a bookmark), then an
     * organisation the Navigator led there, then the most common address.
     */
    static String name(Cluster c, List<Place> places) {
        String chosen = mostCommon(c, 0);
        if (chosen != null) return chosen;
        Place best = null;
        double bestM = NAME_M;
        for (Place p : places) {
            double m = p.metersTo(c.lat, c.lon);
            if (m <= bestM) {
                best = p;
                bestM = m;
            }
        }
        if (best != null) return best.name;
        String org = mostCommon(c, 1);
        return org != null ? org : mostCommon(c, 2);
    }

    /**
     * Where to send the Navigator for a place: a Yandex place within {@link #NAME_M} (its own
     * point — an organisation's entrance), else the latest point the Navigator itself was given
     * for drives there, else the middle of where the car stopped — which can be the far side of
     * the building (seen in the car).
     */
    static double[] point(Cluster c, List<Place> places) {
        Place best = null;
        double bestM = NAME_M;
        for (Place p : places) {
            if (!p.hasGeo()) continue;
            double m = p.metersTo(c.lat, c.lon);
            if (m <= bestM) {
                best = p;
                bestM = m;
            }
        }
        if (best != null) return new double[]{best.lat, best.lon};
        Trip latest = null;
        for (Trip t : c.trips) {
            if (t.hasDest() && (latest == null || t.endMs > latest.endMs)) latest = t;
        }
        if (latest != null) return new double[]{latest.destLat, latest.destLon};
        return new double[]{c.lat, c.lon};
    }

    /** what: 0 — the chosen name, 1 — the Navigator's organisation, 2 — the address. */
    private static String mostCommon(Cluster c, int what) {
        Map<String, Integer> count = new HashMap<>();
        String best = null;
        int bestN = 0;
        for (Trip t : c.trips) {
            String v = what == 0 ? t.name : what == 1 ? t.naviName : t.address;
            if (v == null || v.trim().isEmpty()) continue;
            int n = count.merge(v.trim(), 1, Integer::sum);
            if (n > bestN) {
                best = v.trim();
                bestN = n;
            }
        }
        return best;
    }

    private static String unnamed(Cluster c, Input in) {
        if (!Geo.valid(in.hereLat, in.hereLon)) return "Частое место";
        double km = Geo.meters(c.lat, c.lon, in.hereLat, in.hereLon) / 1000;
        return km < 10 ? String.format(Locale.ROOT, "Место в %.1f км", km).replace('.', ',')
                : String.format(Locale.ROOT, "Место в %d км", Math.round(km));
    }

    private static Place find(List<Place> places, Place.Kind kind) {
        for (Place p : places) if (p.kind == kind && p.hasGeo()) return p;
        return null;
    }

    /** Yandex «Дом», else where most drives end in the evening and at night. */
    static Place homeOrLearned(List<Place> places, List<Cluster> clusters, ZoneId zone) {
        Place p = find(places, Place.Kind.HOME);
        if (p != null) return p;
        Cluster c = mostEnds(clusters, zone, null, false, 19, 5);
        return c == null ? null : new Place(Place.Kind.HOME, "Дом", "", c.lat, c.lon);
    }

    /** Yandex «Работа», else where most workday drives end in the morning (not home). */
    static Place workOrLearned(List<Place> places, List<Cluster> clusters, Place home,
                               ZoneId zone) {
        Place p = find(places, Place.Kind.WORK);
        if (p != null) return p;
        Cluster c = mostEnds(clusters, zone, home, true, 7, 12);
        return c == null ? null : new Place(Place.Kind.WORK, "Работа", "", c.lat, c.lon);
    }

    /** The place with the most drives ending between fromHour and toHour (wrapping midnight). */
    private static Cluster mostEnds(List<Cluster> clusters, ZoneId zone, Place not,
                                    boolean workdays, int fromHour, int toHour) {
        Cluster best = null;
        int bestN = LEARN_MIN - 1;
        for (Cluster c : clusters) {
            if (not != null && not.metersTo(c.lat, c.lon) <= AT_M) continue;
            int n = 0;
            for (Trip t : c.trips) {
                ZonedDateTime e = Instant.ofEpochMilli(t.endMs).atZone(zone);
                if (workdays && isWeekend(e.getDayOfWeek())) continue;
                int h = e.getHour();
                boolean in = fromHour <= toHour ? h >= fromHour && h < toHour
                        : h >= fromHour || h < toHour;
                if (in) n++;
            }
            if (n > bestN) {
                best = c;
                bestN = n;
            }
        }
        return best;
    }
}
