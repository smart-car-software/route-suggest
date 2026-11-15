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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DimTabsTest {

    @Test
    public void aMenuJustOpenedOnThePhone_goesToNavigation() {
        assertTrue(DimTabs.jumpToNavigation(DimTabs.CLOSED, DimTabs.PHONE, 1_000));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.UNKNOWN, DimTabs.PHONE, 0));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.OPEN_OTHER, DimTabs.PHONE, 0));
        assertTrue(DimTabs.jumpToNavigation(DimTabs.PHONE, DimTabs.PHONE, DimTabs.FRESH_MS + 1));
    }

    @Test
    public void thePhoneReachedByTheDriver_stays() {
        assertFalse(DimTabs.jumpToNavigation(DimTabs.NAVIGATION, DimTabs.PHONE, 1_000));
        assertFalse(DimTabs.jumpToNavigation(DimTabs.CLOSED, DimTabs.NAVIGATION, 1_000));
    }
}
