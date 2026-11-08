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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Navigator's own «последние» on the unit: where each route went, and when. Every route
 * built updates its destination's record in the local DataSync database
 * {@code files/datasync/.ext.maps_common@ymapspointshistory1/db.sqlite} (table {@code items},
 * {@code key} = {@code delta:N} / a snapshot, {@code value} = a boost::serialization binary
 * archive): {@code last_used} (ms), {@code latitude}, {@code longitude}, {@code title},
 * {@code description}. Each delta is one change, so until they are synced away the deltas hold
 * every route with its time — two deltas for building, one more for «Поехали». Found on the
 * emulator with the Monjaro mod 30.3.0. Pure.
 *
 * <p>A record starts with {@code u32 13, "pointshistory"}, a service header, {@code u32 len, id}
 * ({@link #recordId}); then its fields ({@link #field}). Checked byte by byte on the emulator.
 */
final class NaviHistory {
    static final String DB = "/data/data/ru.yandex.yandexnavi/files/datasync/"
            + ".ext.maps_common@ymapspointshistory1/db.sqlite";
    /** Building and «Поехали» within this are one route. */
    static final long SAME_ROUTE_MS = 3 * 60_000L;

    private static final byte[] COLLECTION = withLength("pointshistory");

    static final class Point {
        final String id;
        long lastUsed;
        double lat = Double.NaN;
        double lon = Double.NaN;
        String title = "";
        String description = "";

        Point(String id) {
            this.id = id;
        }
    }

    /** One route to a place, at a time. */
    static final class Route {
        final String id;
        final long ms;
        final double lat;
        final double lon;
        final String title;
        final String description;

        Route(String id, long ms, double lat, double lon, String title, String description) {
            this.id = id;
            this.ms = ms;
            this.lat = lat;
            this.lon = lon;
            this.title = title;
            this.description = description;
        }

        String key() {
            return id + "|" + ms;
        }

        Trip trip() {
            return new Trip(0, ms, lat, lon, ms, lat, lon, title.isEmpty() ? null : title,
                    description, true);
        }
    }

    private NaviHistory() {
    }

    /** The records of one value (a delta holds one, a snapshot many). */
    static List<Point> records(byte[] v) {
        List<Point> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int i = indexOf(v, COLLECTION, 0); i >= 0; i = indexOf(v, COLLECTION, i + 1)) starts.add(i);
        for (int k = 0; k < starts.size(); k++) {
            int from = starts.get(k) + COLLECTION.length;
            int to = k + 1 < starts.size() ? starts.get(k + 1) : v.length;
            String id = recordId(v, from, to);
            if (id == null) continue;
            Point p = new Point(id);
            Object t = field(v, from, to, "last_used");
            if (t instanceof Long) p.lastUsed = (Long) t;
            Object la = field(v, from, to, "latitude"), lo = field(v, from, to, "longitude");
            if (la instanceof Double) p.lat = (Double) la;
            if (lo instanceof Double) p.lon = (Double) lo;
            Object ti = field(v, from, to, "title"), de = field(v, from, to, "description");
            if (ti instanceof String) p.title = ((String) ti).trim();
            if (de instanceof String) p.description = ((String) de).trim();
            out.add(p);
        }
        return out;
    }

    /**
     * Routes out of the database: every distinct time a place was used in the deltas — changes
     * made on this unit — building and «Поехали» folded into one (the later). The other values
     * (a snapshot: what came from the account, the phone's routes too) only lend coordinates
     * and names, never a time: a route built on the phone is not a drive.
     */
    static List<Route> routes(List<byte[]> deltas, List<byte[]> others) {
        Map<String, Point> known = new LinkedHashMap<>();
        Map<String, List<Long>> times = new HashMap<>();
        List<byte[]> all = new ArrayList<>(deltas);
        all.addAll(others);
        for (int n = 0; n < all.size(); n++) {
            boolean delta = n < deltas.size();
            for (Point p : records(all.get(n))) {
                Point k = known.computeIfAbsent(p.id, Point::new);
                if (place(p.lat, p.lon)) {
                    k.lat = p.lat;
                    k.lon = p.lon;
                }
                if (!p.title.isEmpty()) k.title = p.title;
                if (!p.description.isEmpty()) k.description = p.description;
                if (delta && p.lastUsed > 0) times.computeIfAbsent(p.id, x -> new ArrayList<>()).add(p.lastUsed);
            }
        }
        List<Route> out = new ArrayList<>();
        for (Point k : known.values()) {
            List<Long> ts = times.get(k.id);
            if (ts == null || !place(k.lat, k.lon)) continue;
            ts.sort(null);
            long groupEnd = -1;
            for (int i = 0; i < ts.size(); i++) {
                long t = ts.get(i);
                boolean last = i + 1 == ts.size() || ts.get(i + 1) - t > SAME_ROUTE_MS;
                if (last && t != groupEnd) {
                    out.add(new Route(k.id, t, k.lat, k.lon, k.title, k.description));
                    groupEnd = t;
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /** The routes kept so far plus the new ones (the deltas are gone once synced), newest kept. */
    static List<Route> accumulate(List<Route> kept, List<Route> fresh, int max) {
        Map<String, Route> all = new LinkedHashMap<>();
        for (Route r : kept) all.put(r.key(), r);
        for (Route r : fresh) {
            boolean near = false;
            for (Route k : kept) {
                if (k.id.equals(r.id) && Math.abs(k.ms - r.ms) <= SAME_ROUTE_MS) near = true;
            }
            if (!near) all.put(r.key(), r);
        }
        List<Route> out = new ArrayList<>(all.values());
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    /**
     * Drives recorded by GPS and routes from the Navigator, without counting one drive twice: a
     * route is dropped when a recorded drive left within 30 min of it and ended within 500 m of
     * its destination — and then gives that drive its name, if it had none.
     */
    static List<Trip> merge(List<Trip> recorded, List<Trip> routes) {
        List<Trip> out = new ArrayList<>(recorded);
        for (Trip r : routes) {
            int same = -1;
            for (int i = 0; i < recorded.size(); i++) {
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

    // ------------------------------------------------------------------ the archive

    /**
     * A field's value. After the name: {@code u32 op} (2 — set when the record is created, 1 —
     * changed), {@code u8 1}, {@code u32 type}, then the value at +9: 03 — float64; 04 —
     * {@code u32 len, UTF-8}; 06 — five zero bytes, then int64 ms at +14; 00 — none.
     */
    private static Object field(byte[] v, int from, int to, String name) {
        byte[] key = withLength(name);
        ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = indexOf(v, key, from); i >= 0 && i < to; i = indexOf(v, key, i + 1)) {
            int p = i + key.length;
            if (p + 9 > to) return null;
            int op = b.getInt(p);
            if ((op != 1 && op != 2) || v[p + 4] != 1) continue;
            int type = b.getInt(p + 5);
            switch (type) {
                case 0x06:
                    if (p + 22 > to) return null;
                    return b.getLong(p + 14);
                case 0x03:
                    if (p + 17 > to) return null;
                    return b.getDouble(p + 9);
                case 0x04:
                    return string(v, p + 9, to);
                default:
                    return null;
            }
        }
        return null;
    }

    /**
     * The record's id: after the collection's name comes a service header (22 bytes in mod
     * 30.3.0), then {@code u32 len, id}. Found as the first length 16–64 followed by that many
     * id characters, so a header of another length does not break it.
     */
    private static String recordId(byte[] v, int from, int to) {
        ByteBuffer b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN);
        for (int p = from; p + 4 <= to && p < from + 96; p++) {
            int len = b.getInt(p);
            if (len < 16 || len > 64 || p + 4 + len > to) continue;
            boolean ok = true;
            for (int k = 0; k < len && ok; k++) {
                int c = v[p + 4 + k] & 0xff;
                ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '_' || c == '.' || c == '-';
            }
            if (ok) return new String(v, p + 4, len, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean place(double lat, double lon) {
        return Geo.valid(lat, lon) && Math.abs(lat) > 1e-6 && Math.abs(lon) > 1e-6;
    }

    /** {@code u32 len, bytes} at p, as UTF-8; null when it does not fit. */
    private static String string(byte[] v, int p, int to) {
        if (p + 4 > to) return null;
        int len = ByteBuffer.wrap(v, p, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 0 || len > 4096 || p + 4 + len > to) return null;
        return new String(v, p + 4, len, StandardCharsets.UTF_8);
    }

    private static byte[] withLength(String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + t.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(t.length).put(t);
        return b.array();
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ kept routes

    static String toJson(List<Route> routes) throws JSONException {
        JSONArray a = new JSONArray();
        for (Route r : routes) {
            a.put(new JSONObject().put("id", r.id).put("ms", r.ms).put("lat", r.lat).put("lon", r.lon)
                    .put("t", r.title).put("d", r.description));
        }
        return a.toString();
    }

    static List<Route> fromJson(String json) {
        List<Route> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Route r = new Route(o.getString("id"), o.getLong("ms"), o.getDouble("lat"), o.getDouble("lon"),
                        o.optString("t"), o.optString("d"));
                // v0.4.0 kept unparsed routes (no id, coordinates ~0): drop them
                if (!r.id.isEmpty() && place(r.lat, r.lon)) out.add(r);
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }
}
