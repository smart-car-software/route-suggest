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

public class AutoGoTest {
    @Test
    public void onlyTheWholeLabel() {
        assertTrue(AutoGoService.matches("Поехали", "поехали"));
        assertTrue(AutoGoService.matches(" Go ", "go"));
        assertFalse(AutoGoService.matches("Go around", "go"));
        assertFalse(AutoGoService.matches("Поехали другим путём", "поехали"));
        assertFalse(AutoGoService.matches(null, "go"));
    }
}
