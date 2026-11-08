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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;

public class YandexDataTest {
    static JSONObject rec(String coll, String id, Object... kv) throws Exception {
        JSONArray fields = new JSONArray();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            String t = v instanceof String ? "string" : v instanceof Double ? "double" : "integer";
            fields.put(new JSONObject().put("field_id", kv[i])
                    .put("value", new JSONObject().put("type", t).put(t, v)));
        }
        return new JSONObject().put("collection_id", coll).put("record_id", id).put("fields", fields);
    }

    static String snap(JSONObject... recs) throws Exception {
        JSONArray items = new JSONArray();
        for (JSONObject r : recs) items.put(r);
        return new JSONObject().put("records", new JSONObject().put("items", items)).toString();
    }

    @Test
    public void homeAndWork() throws Exception {
        String s = snap(rec("common_addresses", "home", "title", "Дом", "latitude", 55.8,
                        "longitude", 49.1, "address_line", "Ленина 1"),
                rec("common_addresses", "work", "title", "Работа", "latitude", 55.75,
                        "longitude", 49.2),
                rec("common_addresses", "other", "latitude", 1.0, "longitude", 2.0));
        List<Place> p = YandexData.addresses(s);
        assertEquals(2, p.size());
        assertEquals(Place.Kind.HOME, p.get(0).kind);
        assertEquals("Дом", p.get(0).name);
        assertEquals("Ленина 1", p.get(0).address);
        assertEquals(Place.Kind.WORK, p.get(1).kind);
        assertEquals(49.2, p.get(1).lon, 1e-9);
    }

    @Test
    public void bookmarks() throws Exception {
        String s = snap(rec("bookmarks", "b1", "title", "Кофемания", "uri", "ymapsbm1://org?oid=1124715036"),
                rec("bookmarks", "b2", "title", "Дача", "uri", "ymapsbm1://pin?ll=37.610000%2C55.750000",
                        "description", "СНТ Ромашка"),
                rec("bookmarks", "b3", "title", "Ильяс", "uri", "ymapsbm1://pin?ll=30.3,59.9"),
                rec("bookmarks", "f1", "type", "folder", "title", "Избранное", "uri", "x"),
                rec("bookmarks", "b4", "title", "", "uri", "ymapsbm1://pin?ll=1,2"));
        List<Place> p = YandexData.bookmarks(s);
        assertEquals(3, p.size());
        assertFalse(p.get(0).hasGeo());
        assertEquals(55.75, p.get(1).lat, 1e-9);
        assertEquals(37.61, p.get(1).lon, 1e-9);
        assertEquals("СНТ Ромашка", p.get(1).address);
        assertEquals(59.9, p.get(2).lat, 1e-9);
    }

    @Test
    public void geoFromProtobuf() {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x0a).put((byte) 0x01).put((byte) 0x41); // some other field first
        b.put((byte) 0x22).put((byte) 0x0a).put((byte) 0x0d).putFloat(49.1f).put((byte) 0x15).putFloat(55.8f);
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
        double[] g = YandexData.lonLatFromUri("ymapsbm1://geo?data=" + data + ",");
        assertEquals(55.8, g[0], 1e-5);
        assertEquals(49.1, g[1], 1e-5);
        assertNull(YandexData.geoFromData("bm90IGEgcG9pbnQ"));
    }

    @Test
    public void cacheRoundTrip() throws Exception {
        List<Place> p = YandexData.fromJson(YandexData.toJson(java.util.Arrays.asList(
                new Place(Place.Kind.HOME, "Дом", "Ленина 1", 55.8, 49.1),
                new Place(Place.Kind.BOOKMARK, "Кофемания", "", Double.NaN, Double.NaN))));
        assertEquals(2, p.size());
        assertTrue(p.get(0).hasGeo());
        assertFalse(p.get(1).hasGeo());
        assertTrue(YandexData.fromJson("garbage").isEmpty());
    }
}
