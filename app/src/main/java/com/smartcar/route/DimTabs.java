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

/**
 * The cluster menu's tabs as ECarX numbers them ({@code IDimMenuInteraction.MENU_TAB_*}, read
 * from the AdaptAPI jar), and when to take the driver from the phone tab to navigation. Pure.
 */
final class DimTabs {
    static final int UNKNOWN = -1;
    static final int OPEN_OTHER = 0;
    static final int PHONE = 1;
    static final int NAVIGATION = 2;
    static final int CLOSED = 5;
    /** Longer than this since the last tab change, and the menu counts as opened afresh. */
    static final long FRESH_MS = 15_000L;

    private DimTabs() {
    }

    /**
     * The wheel's menu button opens the cluster menu on the phone tab; the driver wants
     * navigation (the user's requirement). Only a menu just opened is moved: phone reached from
     * navigation is the driver's own choice (barrier-opener's gates are there).
     */
    static boolean jumpToNavigation(int previous, int now, long msSincePrevious) {
        if (now != PHONE) return false;
        return previous == UNKNOWN || previous == OPEN_OTHER || previous == CLOSED
                || msSincePrevious > FRESH_MS;
    }
}
