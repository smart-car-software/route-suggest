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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which calendar events are worth a route: going on or starting within the hour, with
 * somewhere to drive to — the event's own coordinates, a Yandex place named in its location or
 * title («Дом», a bookmark), or an address. Left out: all-day and «free» events, cancelled ones,
 * video calls, and in a work calendar a location that is not an address (a meeting room).
 * Copies of one event in two calendars are one. Pure.
 */
final class EventFilter {
    static final int WINDOW_MIN = 60;

    private static final Pattern ONLINE = Pattern.compile(
            "https?://|zoom|teams|telemost|телемост|meet\\.google|skype|webex|онлайн|online|видеозвон|ktalk|ктолк",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Looks like a street address, not «Переговорка 5.12». */
    private static final Pattern ADDRESS = Pattern.compile(
            "(^|[\\s,])(ул\\.|улица|пр-т|просп\\.|проспект|пер\\.|переулок|ш\\.|шоссе|б-р|бульвар"
                    + "|наб\\.|набережная|пл\\.|площадь|тракт|д\\.\\s*\\d|дом\\s*\\d)(?=[\\s\\d,]|$)"
                    + "|,\\s*\\d+[а-яa-z]?(\\s|,|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static final class Entry {
        String title = "";
        long startMs;
        long endMs;
        boolean allDay;
        /** Free / transparent / cancelled: not taking the time. */
        boolean free;
        String location = "";
        double lat = Double.NaN;
        double lon = Double.NaN;
        /** From a work (Exchange) calendar. */
        boolean work;
    }

    private EventFilter() {
    }

    static List<CalEvent> filter(List<Entry> in, long nowMs, List<Place> places) {
        long until = nowMs + WINDOW_MIN * 60_000L;
        List<Entry> sorted = new ArrayList<>(in);
        sorted.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        Set<String> seen = new HashSet<>();
        List<CalEvent> out = new ArrayList<>();
        for (Entry e : sorted) {
            if (e.allDay || e.free || e.endMs <= nowMs || e.startMs > until) continue;
            String title = e.title == null || e.title.trim().isEmpty() ? "Событие" : e.title.trim();
            if (!seen.add(norm(title) + "|" + e.startMs + "|" + e.endMs)) continue;
            String location = e.location == null ? "" : e.location.trim();
            if (ONLINE.matcher(location).find()) location = "";
            double lat = e.lat, lon = e.lon;
            Place place = null;
            if (!Geo.valid(lat, lon)) {
                place = location.isEmpty() ? null : find(location, places);
                if (place == null) place = find(title, places);
                if (place != null) {
                    lat = place.lat;
                    lon = place.lon;
                }
            }
            if (!Geo.valid(lat, lon)) {
                if (location.isEmpty()) continue;
                if (e.work && !looksLikeAddress(location)) continue;
            }
            out.add(new CalEvent(title, e.startMs, e.endMs,
                    !location.isEmpty() ? location : place != null ? place.address : "",
                    place != null ? place.name : "", lat, lon));
        }
        return out;
    }

    static boolean looksLikeAddress(String s) {
        return ADDRESS.matcher(s).find();
    }

    /** A Yandex place the text names: by name or by the very address. */
    static Place find(String text, List<Place> places) {
        String k = norm(text);
        if (k.isEmpty()) return null;
        for (Place p : places) {
            if (!p.hasGeo()) continue;
            if (norm(p.name).equals(k) || (!p.address.isEmpty() && norm(p.address).equals(k))) return p;
        }
        return null;
    }

    static String norm(String s) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT).replace('ё', 'е');
        t = t.replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        return t.trim().replaceAll("\\s+", " ");
    }
}
