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

/**
 * Car Notification Service asks for the menu's rows (its tab opened, or it started). Exported,
 * so it does nothing but send the rows again: the pick comes by its own PendingIntent.
 */
public final class MenuWantedReceiver extends BroadcastReceiver {
    static final String ACTION = "com.smartcar.notify.action.MENU_WANTED";

    @Override
    public void onReceive(Context c, Intent i) {
        if (ACTION.equals(i.getAction()) && MenuTab.on(c)) {
            RouteService.send(c, RouteService.ACTION_MENU_REFRESH);
        }
    }
}
