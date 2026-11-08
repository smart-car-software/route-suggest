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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user's places in Yandex DataSync, as the Maps web page reads them (calendar-bot does the
 * same, see its calbot/yandex.py):
 * <ul>
 *   <li>{@code .ext.profile@addresses} — collection {@code common_addresses}, records
 *       {@code home} and {@code work} with {@code latitude}, {@code longitude},
 *       {@code address_line};</li>
 *   <li>{@code .ext.maps_common@ymapsbookmarks1} — collection {@code bookmarks}: {@code title},
 *       {@code uri} ({@code ymapsbm1://pin?ll=lon,lat}, {@code ymapsbm1://geo?data=<protobuf>},
 *       {@code ymapsbm1://org?oid=…} — an organisation, no coordinates), {@code description}.</li>
 * </ul>
 * Pure: snapshot JSON in, places out.
 */
final class YandexData {
    static final String BASE = "https://cloud-api.yandex.ru/maps_common/v1/data/app/databases/";
    static final String ADDRESSES = BASE + ".ext.profile@addresses/snapshot";
    static final String BOOKMARKS = BASE + ".ext.maps_common@ymapsbookmarks1/snapshot";
    /** Routes built in the Navigator / Maps: from, to, when (collection {@code ridehistory}). */
    static final String RIDES = BASE + ".ext.maps_common@ymapsridehistory1/snapshot";
    /** «Последние» places (collection {@code pointshistory}). */
    static final String POINTS = BASE + ".ext.maps_common@ymapspointshistory1/snapshot";

    private static final Pattern LL = Pattern.compile("ll=([-\\d.]+)(?:,|%2C)([-\\d.]+)");
    private static final Pattern DATA = Pattern.compile("[?&]data=([^&]+)");

    private YandexData() {
    }

    /** A DataSync typed value → Java (String, Double, Long, Boolean, List). */
    static Object value(JSONObject v) {
        if (v == null) return null;
        String t = v.optString("type", "");
        if (t.isEmpty()) return null;
        if ("list".equals(t)) {
            List<Object> out = new ArrayList<>();
            JSONArray a = v.optJSONArray("list");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(value(a.optJSONObject(i)));
            return out;
        }
        return v.opt(t);
    }

    /** [{"collection", "id", fields…}] of a snapshot. */
    static List<JSONObject> records(String snapshot) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        JSONObject recs = new JSONObject(snapshot).optJSONObject("records");
        JSONArray items = recs == null ? null : recs.optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            JSONObject d = new JSONObject();
            d.put("collection", r.optString("collection_id"));
            d.put("id", r.optString("record_id"));
            JSONArray fields = r.optJSONArray("fields");
            if (fields != null) {
                for (int j = 0; j < fields.length(); j++) {
                    JSONObject f = fields.optJSONObject(j);
                    if (f == null) continue;
                    Object v = value(f.optJSONObject("value"));
                    if (v != null && !(v instanceof List)) d.put(f.optString("field_id"), v);
                }
            }
            out.add(d);
        }
        return out;
    }

    /** «Дом» and «Работа». */
    static List<Place> addresses(String snapshot) throws JSONException {
        List<Place> out = new ArrayList<>();
        for (JSONObject r : records(snapshot)) {
            String id = r.optString("id");
            Place.Kind kind = "home".equals(id) ? Place.Kind.HOME
                    : "work".equals(id) ? Place.Kind.WORK : null;
            if (kind == null) continue;
            double lat = r.optDouble("latitude", Double.NaN), lon = r.optDouble("longitude", Double.NaN);
            if (!Geo.valid(lat, lon)) continue;
            out.add(new Place(kind, kind == Place.Kind.HOME ? "Дом" : "Работа",
                    r.optString("address_line", ""), lat, lon));
        }
        return out;
    }

    static List<Place> bookmarks(String snapshot) throws JSONException {
        List<Place> out = new ArrayList<>();
        for (JSONObject r : records(snapshot)) {
            String coll = r.optString("collection");
            String type = r.optString("type", "");
            if (!(coll.isEmpty() || "bookmarks".equals(coll))) continue;
            if (!(type.isEmpty() || "bookmark".equals(type))) continue;
            String uri = r.optString("uri", "");
            String title = r.optString("title", r.optString("name", "")).trim().replaceAll("\\s+", " ");
            if (uri.isEmpty() || title.isEmpty()) continue;
            double[] geo = lonLatFromUri(uri);
            double lat = r.optDouble("lat", r.optDouble("latitude", Double.NaN));
            double lon = r.optDouble("lon", r.optDouble("longitude", Double.NaN));
            if (Geo.valid(lat, lon)) geo = new double[]{lat, lon};
            out.add(new Place(Place.Kind.BOOKMARK, title.length() > 60 ? title.substring(0, 60) : title,
                    r.optString("description", r.optString("address", "")).trim(),
                    geo == null ? Double.NaN : geo[0], geo == null ? Double.NaN : geo[1]));
        }
        return out;
    }

    /**
     * The routes of the ride history driven by car, as drives: the start is when the route was
     * built (about when the car left), the end is the destination — its time unknown, taken as
     * the start. {@code name} is the destination's title, {@code address} its description.
     */
    static List<Trip> rides(String snapshot) throws JSONException {
        List<Trip> out = new ArrayList<>();
        for (JSONObject r : records(snapshot)) {
            String type = r.optString("ride_type_v2", r.optString("ride_type", "car"));
            if (!"car".equals(type)) continue;
            double fLat = r.optDouble("from_latitude", Double.NaN), fLon = r.optDouble("from_longitude", Double.NaN);
            double tLat = r.optDouble("to_latitude", Double.NaN), tLon = r.optDouble("to_longitude", Double.NaN);
            if (!Geo.valid(tLat, tLon)) continue;
            long ts;
            try {
                ts = java.time.OffsetDateTime.parse(r.optString("timestamp")).toInstant().toEpochMilli();
            } catch (RuntimeException e) {
                continue;
            }
            if (!Geo.valid(fLat, fLon)) {
                fLat = tLat;
                fLon = tLon;
            }
            String title = r.optString("to_title", "").trim();
            out.add(new Trip(0, ts, fLat, fLon, ts, tLat, tLon, title.isEmpty() ? null : title,
                    r.optString("to_description", "").trim(), true));
        }
        return out;
    }

    /** «Последние» with coordinates, as places for naming. */
    static List<Place> recent(String snapshot) throws JSONException {
        List<Place> out = new ArrayList<>();
        for (JSONObject r : records(snapshot)) {
            double lat = r.optDouble("latitude", Double.NaN), lon = r.optDouble("longitude", Double.NaN);
            String title = r.optString("title", "").trim();
            if (!Geo.valid(lat, lon) || title.isEmpty()) continue;
            out.add(new Place(Place.Kind.RECENT, title.length() > 60 ? title.substring(0, 60) : title,
                    r.optString("description", "").trim(), lat, lon));
        }
        return out;
    }

    static String tripsToJson(List<Trip> trips) throws JSONException {
        JSONArray a = new JSONArray();
        for (Trip t : trips) {
            a.put(new JSONObject().put("s", t.startMs).put("fa", t.startLat).put("fo", t.startLon)
                    .put("ta", t.endLat).put("to", t.endLon).put("n", t.name == null ? "" : t.name)
                    .put("d", t.address == null ? "" : t.address));
        }
        return a.toString();
    }

    static List<Trip> tripsFromJson(String json) {
        List<Trip> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String n = o.optString("n");
                out.add(new Trip(0, o.getLong("s"), o.getDouble("fa"), o.getDouble("fo"), o.getLong("s"),
                        o.getDouble("ta"), o.getDouble("to"), n.isEmpty() ? null : n, o.optString("d"), true));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    /**
     * Drives recorded by GPS and routes from the ride history, without counting one drive
     * twice: a route is dropped when a recorded drive left within 30 min of it and ended within
     * 500 m of its destination — and then gives that drive its name, if it had none.
     */
    static List<Trip> merge(List<Trip> recorded, List<Trip> rides) {
        List<Trip> out = new ArrayList<>(recorded);
        for (Trip r : rides) {
            int same = -1;
            for (int i = 0; i < out.size() && i < recorded.size(); i++) {
                Trip g = out.get(i);
                if (Math.abs(g.startMs - r.startMs) <= 30 * 60_000L
                        && Geo.meters(g.endLat, g.endLon, r.endLat, r.endLon) <= 500) {
                    same = i;
                    break;
                }
            }
            if (same < 0) {
                out.add(r);
            } else if (out.get(same).name == null && r.name != null) {
                Trip g = out.get(same);
                out.set(same, g.withLabel(r.name, g.address, true));
            }
        }
        return out;
    }

    /** (lat, lon) of a bookmark URI, or null (an organisation: only its id). */
    static double[] lonLatFromUri(String uri) {
        Matcher m = LL.matcher(uri);
        if (m.find()) {
            try {
                double lon = Double.parseDouble(m.group(1)), lat = Double.parseDouble(m.group(2));
                return Geo.valid(lat, lon) ? new double[]{lat, lon} : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        m = DATA.matcher(uri);
        if (m.find()) {
            try {
                return geoFromData(URLDecoder.decode(m.group(1), "UTF-8"));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code ymapsbm1://geo?data=…}: a protobuf in base64 whose field 4 is the point,
     * {1: lon, 2: lat} as float32 (bytes 22 0A 0D &lt;lon&gt; 15 &lt;lat&gt;).
     */
    static double[] geoFromData(String data) {
        Matcher m = Pattern.compile("^[A-Za-z0-9_+/-]*").matcher(data);
        if (!m.find()) return null;
        String s = m.group().replace('+', '-').replace('/', '_');
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(s + "===".substring(0, (4 - s.length() % 4) % 4));
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (int i = 0; i + 12 <= raw.length; i++) {
            if (raw[i] == 0x22 && raw[i + 1] == 0x0a && raw[i + 2] == 0x0d && raw[i + 7] == 0x15) {
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                double lon = b.getFloat(i + 3), lat = b.getFloat(i + 8);
                if (!Geo.valid(lat, lon)) return null;
                return new double[]{Math.round(lat * 1e6) / 1e6, Math.round(lon * 1e6) / 1e6};
            }
        }
        return null;
    }

    /** Places to the cache file and back. */
    static String toJson(List<Place> places) throws JSONException {
        JSONArray a = new JSONArray();
        for (Place p : places) {
            JSONObject o = new JSONObject().put("kind", p.kind.name()).put("name", p.name)
                    .put("address", p.address);
            if (p.hasGeo()) o.put("lat", p.lat).put("lon", p.lon);
            a.put(o);
        }
        return a.toString();
    }

    static List<Place> fromJson(String json) {
        List<Place> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Place(Place.Kind.valueOf(o.getString("kind")), o.getString("name"),
                        o.optString("address"), o.optDouble("lat", Double.NaN),
                        o.optDouble("lon", Double.NaN)));
            }
        } catch (JSONException | IllegalArgumentException e) {
            out.clear();
        }
        return out;
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
