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

/** Nominatim's answer, cookies, the saved rows. */
public class ParsingTest {
    @Test
    public void nominatimShortAddress() {
        assertEquals("улица Баумана 1", Geocode.shortAddress("{\"name\":\"\",\"address\":"
                + "{\"road\":\"улица Баумана\",\"house_number\":\"1\",\"city\":\"Казань\"}}"));
        assertEquals("Ашан", Geocode.shortAddress("{\"name\":\"Ашан\",\"address\":{\"city\":\"Казань\"}}"));
        assertEquals("Казань", Geocode.shortAddress("{\"address\":{\"city\":\"Казань\"}}"));
        assertEquals("", Geocode.shortAddress("{\"error\":\"Unable to geocode\"}"));
    }

    @Test
    public void forwardGeocodeTakesTheNearest() {
        String json = "[{\"lat\":\"55.75\",\"lon\":\"37.61\",\"display_name\":\"Кремль, Москва\"},"
                + "{\"lat\":\"55.7987\",\"lon\":\"49.1056\",\"display_name\":\"Кремль, Казань\"}]";
        double[] g = Geocode.nearest(json, 55.79, 49.12); // the car is in Kazan
        assertEquals(55.7987, g[0], 1e-9);
        assertEquals(55.75, Geocode.nearest(json, Double.NaN, Double.NaN)[0], 1e-9); // no fix: the first
        assertNull(Geocode.nearest("[{\"lat\":\"55.75\",\"lon\":\"37.61\"}]", 55.79, 49.12)); // only far away
        assertNull(Geocode.nearest("[]", 55.79, 49.12));
        assertNull(Geocode.nearest("{\"error\":1}", 55.79, 49.12));
    }

    @Test
    public void cookies() {
        CookieJar jar = new CookieJar();
        long now = 1_000_000L;
        jar.store("Session_id=abc; Domain=.yandex.ru; Path=/; Secure; HttpOnly", "passport.yandex.ru", now);
        jar.store("yandexuid=42; Domain=yandex.ru; Path=/; Max-Age=3600", "passport.yandex.ru", now);
        jar.store("only=here; Path=/", "passport.yandex.ru", now);
        jar.store("evil=1; Domain=example.com", "passport.yandex.ru", now);
        assertTrue(jar.has("Session_id"));
        assertFalse(jar.has("evil"));
        String h = jar.header("cloud-api.yandex.ru", "/maps_common/", true, now);
        assertEquals("Session_id=abc; yandexuid=42", h);
        assertEquals("Session_id=abc; yandexuid=42; only=here",
                jar.header("passport.yandex.ru", "/", true, now));
        assertEquals("yandexuid=42", jar.header("yandex.ru", "/", false, now)); // not secure
        assertEquals("Session_id=abc", jar.header("yandex.ru", "/", true, now + 3_601_000L)
                .replace("; only=here", ""));
        CookieJar again = new CookieJar();
        again.restore(jar.save());
        assertEquals(h, again.header("cloud-api.yandex.ru", "/", true, now));
        jar.store("Session_id=; Domain=.yandex.ru; Path=/; Max-Age=0", "passport.yandex.ru", now);
        assertFalse(jar.has("Session_id"));
        assertNull(new CookieJar().header("yandex.ru", "/", true, now));
    }

    @Test
    public void suggestionRoundTrip() throws Exception {
        Suggestion s = new Suggestion(Suggestion.Kind.EVENT, "Врач · 18:30", "Врач", Double.NaN,
                Double.NaN, "Чистопольская 20");
        Suggestion back = Suggestion.fromJson(s.toJson());
        assertEquals(s.row, back.row);
        assertEquals(s.query, back.query);
        assertFalse(back.hasGeo());
        assertEquals(Suggestion.Kind.EVENT, back.kind);
    }
}
