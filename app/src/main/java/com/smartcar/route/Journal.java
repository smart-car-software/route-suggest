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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the app decided and what the unit told it, the last {@link #KEEP} lines, shown in the
 * app: why there was no card at start, which tab the cluster reported, what the joystick sent.
 * The car is where these questions come up, and its logcat is out of reach. Kinds and counts
 * only: never a place, an address or an event title.
 */
final class Journal {
    static final int KEEP = 40;
    private static final String PREFS = "journal";

    private Journal() {
    }

    static synchronized void add(Context c, String line) {
        DebugLog.i("Journal", line);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> all = new ArrayList<>(Arrays.asList(p.getString("lines", "").split("\n")));
        all.removeIf(String::isEmpty);
        all.add(new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + line);
        while (all.size() > KEEP) all.remove(0);
        p.edit().putString("lines", String.join("\n", all)).apply();
    }

    /** Newest first. */
    static synchronized String text(Context c) {
        List<String> all = new ArrayList<>(Arrays.asList(c.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).getString("lines", "").split("\n")));
        all.removeIf(String::isEmpty);
        java.util.Collections.reverse(all);
        return String.join("\n", all);
    }
}
