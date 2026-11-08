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

import java.io.IOException;
import java.util.Locale;

/**
 * A short address for where a drive ended («Баумана 1»), from OpenStreetMap's Nominatim (as
 * calendar-bot does for a geolocation): a place nobody bookmarked still gets a readable row.
 * One request per drive, nothing else is sent.
 */
final class Geocode {
    static final String URL = "https://nominatim.openstreetmap.org/reverse";

    private Geocode() {
    }

    /** Blocking; "" when there is no answer. */
    static String reverse(double lat, double lon) {
        String url = String.format(Locale.ROOT, "%s?lat=%.6f&lon=%.6f&format=jsonv2&zoom=18"
                + "&addressdetails=1&accept-language=ru", URL, lat, lon);
        try {
            Http.Response r = Http.call("GET", url, Http.headers("User-Agent",
                    "route-suggest/" + BuildConfig.VERSION_NAME + " (smart-car-software)"),
                    null, null, null);
            return r.ok() ? shortAddress(r.body) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** «Улица 5», else the object's name, else the suburb/town. Pure. */
    static String shortAddress(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject a = o.optJSONObject("address");
            if (a == null) return "";
            String road = first(a, "road", "pedestrian", "footway", "street");
            String house = a.optString("house_number", "");
            if (!road.isEmpty()) return house.isEmpty() ? road : road + " " + house;
            String name = o.optString("name", "");
            if (!name.isEmpty()) return name;
            return first(a, "neighbourhood", "suburb", "village", "town", "city");
        } catch (JSONException e) {
            return "";
        }
    }

    private static String first(JSONObject a, String... keys) {
        for (String k : keys) {
            String v = a.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
