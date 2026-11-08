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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A calendar event as calendar-bot's {@code GET /v1/car/events} gives it: going on now or
 * starting within the hour, with coordinates or at least an address.
 */
final class CalEvent {
    final String title;
    final long startMs;
    final long endMs;
    final String location;
    final String place;
    final double lat;
    final double lon;

    CalEvent(String title, long startMs, long endMs, String location, String place, double lat,
             double lon) {
        this.title = title;
        this.startMs = startMs;
        this.endMs = endMs;
        this.location = location == null ? "" : location;
        this.place = place == null ? "" : place;
        this.lat = lat;
        this.lon = lon;
    }

    boolean hasGeo() {
        return Geo.valid(lat, lon);
    }

    /** The bot's JSON → events; a malformed item is skipped, not the whole answer. */
    static List<CalEvent> parse(String json) throws JSONException {
        JSONArray arr = new JSONObject(json).optJSONArray("events");
        List<CalEvent> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            try {
                String title = o.optString("title", "").trim();
                if (title.isEmpty()) continue;
                out.add(new CalEvent(title,
                        OffsetDateTime.parse(o.getString("start")).toInstant().toEpochMilli(),
                        OffsetDateTime.parse(o.getString("end")).toInstant().toEpochMilli(),
                        o.optString("location", ""), o.optString("place", ""),
                        o.isNull("lat") ? Double.NaN : o.optDouble("lat", Double.NaN),
                        o.isNull("lon") ? Double.NaN : o.optDouble("lon", Double.NaN)));
            } catch (RuntimeException | JSONException e) {
                // a date the parser does not take: leave that one out
            }
        }
        return out;
    }
}
