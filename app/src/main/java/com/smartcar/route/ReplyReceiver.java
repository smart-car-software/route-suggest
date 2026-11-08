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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Car Notification Service's answer to «Куда едем?»: a row picked with ⏯ starts that route. */
public final class ReplyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String result = i.getStringExtra("result");
        DebugLog.i("Reply", "result " + result);
        if (NotifyClient.RESULT_ANSWERED.equals(result)) {
            RouteService.choose(c, i.getIntExtra("index", -1));
        } else if (NotifyClient.RESULT_UNAVAILABLE.equals(result)) {
            // No overlay permission there: the head unit's own screen, by touch.
            MainActivity.showSuggestions(c);
        }
    }
}
