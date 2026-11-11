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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Yandex's own points for places known by their text (an organisation bookmark, an event's
 * address): where the Navigator was sent when Alice built the route for that row, read from its
 * history ({@link NaviHistory#builtBetween}). With the network Alice still leads; without it the
 * route goes to this point. The user's choice over Yandex's geocoder API (a key, a 30-day limit
 * on keeping its answers).
 */
final class Learned {
    private static final String PREFS = "learned";
    /** The Navigator records the route within this after the choice (a cold map can take a minute). */
    static final long WINDOW_MS = 5 * 60_000L;

    private Learned() {
    }

    static String key(String query) {
        return EventFilter.norm(query);
    }

    static Map<String, double[]> load(Context c) {
        Map<String, double[]> out = new HashMap<>();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (!(e.getValue() instanceof String)) continue;
            String[] p = ((String) e.getValue()).split(",");
            try {
                if (p.length == 2) out.put(e.getKey(), new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void put(Context c, String query, double lat, double lon) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(key(query), lat + "," + lon).apply();
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
