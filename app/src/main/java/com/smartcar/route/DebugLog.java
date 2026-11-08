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

import android.util.Log;

/** Logcat only. Never coordinates or event titles: logcat is readable over the unit's ADB. */
final class DebugLog {
    private DebugLog() {
    }

    static void i(String tag, String msg) {
        Log.i("RouteSuggest/" + tag, msg);
    }

    static void w(String tag, String msg) {
        Log.w("RouteSuggest/" + tag, msg);
    }
}
