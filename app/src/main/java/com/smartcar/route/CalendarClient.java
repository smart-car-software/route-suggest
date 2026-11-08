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

import java.io.IOException;
import java.util.List;

/**
 * calendar-bot's {@code GET /v1/car/events}: the calendars are read on the home server, where
 * the iCloud and Exchange credentials already are, not from the car. The address and the token
 * come with the build ({@code CAR_API_URL}, {@code CAR_API_TOKEN} from the repository secrets).
 */
final class CalendarClient {
    private CalendarClient() {
    }

    static boolean configured() {
        return !BuildConfig.CAR_API_URL.isEmpty() && !BuildConfig.CAR_API_TOKEN.isEmpty();
    }

    /** Blocking. */
    static List<CalEvent> fetch() throws IOException {
        if (!configured()) throw new IOException("календарь не настроен в сборке");
        String base = BuildConfig.CAR_API_URL.replaceAll("/+$", "");
        Http.Response r = Http.call("GET", base + "/v1/car/events",
                Http.headers("Authorization", "Bearer " + BuildConfig.CAR_API_TOKEN,
                        "Accept", "application/json", "User-Agent", "route-suggest/" + BuildConfig.VERSION_NAME),
                null, null, null);
        if (r.code == 401) throw new IOException("календарь: неверный токен");
        if (!r.ok()) throw new IOException("календарь: HTTP " + r.code);
        try {
            return CalEvent.parse(r.body);
        } catch (JSONException e) {
            throw new IOException("календарь ответил не JSON");
        }
    }
}
