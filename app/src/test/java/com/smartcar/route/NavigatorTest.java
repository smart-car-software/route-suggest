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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NavigatorTest {
    static Suggestion s(double lat, double lon, String query) {
        return new Suggestion(Suggestion.Kind.FREQUENT, "row", "", lat, lon, query);
    }

    @Test
    public void textGoesToAliceWhoLeadsAtOnce() {
        List<String[]> c = Navigator.candidates(s(Double.NaN, Double.NaN, "Ice Power, Зорге 66"), true);
        assertEquals(Navigator.NAVI, c.get(0)[0]);
        assertEquals("yandexnavi://ask_alice?text=%D0%9C%D0%B0%D1%80%D1%88%D1%80%D1%83%D1%82%20%D0%B4%D0%BE"
                + "%20Ice%20Power%2C%20%D0%97%D0%BE%D1%80%D0%B3%D0%B5%2066%20%D0%B5%D0%B4%D0%B5%D0%BC",
                c.get(0)[1]);
        assertTrue(Navigator.viaAlice(s(Double.NaN, Double.NaN, "Ice Power"), true));
        // Offline: the search, the last resort.
        List<String[]> off = Navigator.candidates(s(Double.NaN, Double.NaN, "Ice Power"), false);
        assertEquals("yandexnavi://map_search?text=Ice%20Power", off.get(0)[1]);
    }

    @Test
    public void geocodedPointIsTheFallbackForAlice() {
        List<String[]> c = Navigator.candidates(s(55.8, 49.1, "Баумана 5"), true);
        assertTrue(c.get(0)[1].startsWith("yandexnavi://ask_alice?text="));
        assertEquals("yandexnavi://build_route_on_map?lat_to=55.800000&lon_to=49.100000", c.get(1)[1]);
        List<String[]> off = Navigator.candidates(s(55.8, 49.1, "Баумана 5"), false);
        assertTrue(off.get(0)[1].startsWith("yandexnavi://build_route_on_map"));
    }

    @Test
    public void coordinatesNeverToAlice() {
        // She reads them as longitude, latitude (seen on the emulator).
        assertFalse(Navigator.viaAlice(s(Double.NaN, Double.NaN, "55.7963, 49.1088"), true));
        List<String[]> c = Navigator.candidates(s(Double.NaN, Double.NaN, "55,7963; 49,1088"), true);
        assertEquals("yandexnavi://build_route_on_map?lat_to=55.796300&lon_to=49.108800", c.get(0)[1]);
        assertFalse(Navigator.viaAlice(s(55.8, 49.1, ""), true));
        assertNull(Navigator.latLon("Баумана 82"));
    }
}
