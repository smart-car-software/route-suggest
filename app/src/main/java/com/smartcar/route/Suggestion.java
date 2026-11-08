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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One row of «Куда едем?»: what the driver reads ({@link #row}) and where the Navigator goes —
 * coordinates, or {@link #query} to search for when there are none (an event with only an
 * address, an organisation bookmark).
 */
final class Suggestion {
    enum Kind { EVENT, ROUTINE, FREQUENT, HOME, WORK }

    final Kind kind;
    final String row;
    /** The destination's name, remembered with the trip when the drive ends there. */
    final String name;
    final double lat;
    final double lon;
    final String query;

    Suggestion(Kind kind, String row, String name, double lat, double lon, String query) {
        this.kind = kind;
        this.row = row;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.query = query == null ? "" : query;
    }

    boolean hasGeo() {
        return Geo.valid(lat, lon);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", kind.name());
        o.put("row", row);
        o.put("name", name);
        if (hasGeo()) {
            o.put("lat", lat);
            o.put("lon", lon);
        }
        o.put("query", query);
        return o;
    }

    static Suggestion fromJson(JSONObject o) {
        return new Suggestion(Kind.valueOf(o.optString("kind", "FREQUENT")), o.optString("row"),
                o.optString("name"), o.optDouble("lat", Double.NaN), o.optDouble("lon", Double.NaN),
                o.optString("query", ""));
    }
}
