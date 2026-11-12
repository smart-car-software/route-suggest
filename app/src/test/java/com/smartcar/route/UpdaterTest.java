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

public class UpdaterTest {
    @Test
    public void versions() {
        assertTrue(Updater.newer("v0.9.0", "v0.8.1"));
        assertTrue(Updater.newer("v0.8.10", "v0.8.9"));
        assertTrue(Updater.newer("v1.0", "v0.99.0"));
        assertFalse(Updater.newer("v0.8.1", "v0.8.1"));
        assertFalse(Updater.newer("v0.8.0", "v0.8.1"));
        assertFalse(Updater.newer("nightly", "v0.8.1"));
        assertNull(Updater.version("x"));
    }

    @Test
    public void latestRelease() throws Exception {
        Updater.Release r = Updater.parse("{\"tag_name\":\"v0.9.0\",\"body\":\"- меню\",\"assets\":["
                + "{\"name\":\"checksums.txt\",\"browser_download_url\":\"https://x/c\"},"
                + "{\"name\":\"route-suggest-v0.9.0.apk\",\"browser_download_url\":\"https://x/a.apk\"}]}");
        assertEquals("v0.9.0", r.tag);
        assertEquals("https://x/a.apk", r.apkUrl);
        assertEquals("- меню", r.notes);
        assertNull(Updater.parse("{\"tag_name\":\"v0.9.0\",\"assets\":[]}"));
    }
}
