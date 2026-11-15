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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/**
 * «Куда едем?» in the cluster's own menu: every place ({@link Suggester#menu}), the most likely
 * first, on its navigation tab. Car Notification Service draws it and reads the joystick (the
 * menu moved there so barrier-opener's gates can have the phone tab); this app sends the rows
 * and gets the pressed one back ({@link RouteService#ACTION_MENU_PICK}). The service asks for the
 * rows again whenever the tab opens ({@link MenuWantedReceiver}).
 */
final class MenuTab {
    static final String PREF = "cluster_menu";
    static final String ID = "com.smartcar.route";
    /** ECarX's navigation tab of the cluster menu. */
    static final int DIM_NAVIGATION = 2;

    private MenuTab() {
    }

    static boolean on(Context c) {
        return RouteService.prefs(c).getBoolean(PREF, true);
    }

    /** Sends the rows; the key of each is its text, which the pick brings back. */
    static void publish(Context c, List<Suggestion> rows) {
        String[] text = new String[rows.size()];
        for (int n = 0; n < text.length; n++) text[n] = rows.get(n).row;
        Intent pick = new Intent(c, RouteService.class).setAction(RouteService.ACTION_MENU_PICK);
        NotifyClient.menuTab(c, ID, DIM_NAVIGATION, c.getString(R.string.card_title), text,
                c.getString(R.string.menu_empty), 10, PendingIntent.getForegroundService(c, 7, pick,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
    }

    static void remove(Context c) {
        NotifyClient.menuRemove(c, ID);
    }

    static void trial(Context c) {
        NotifyClient.menuTrial(c, ID);
    }
}
