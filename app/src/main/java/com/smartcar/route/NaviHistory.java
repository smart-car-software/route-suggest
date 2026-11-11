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
    /** Deltas this close are one burst (building: two within ~0.5 s; AutoGo's «Поехали» ~1 s on). */
    static final long BURST_MS = 1_500L;

    private static final byte[] COLLECTION = withLength("pointshistory");

    static final class Point {
        final String id;
        long lastUsed;
        double lat = Double.NaN;
        double lon = Double.NaN;
        String title = "";
        String description = "";
        /** Has a uri: an organisation, so its title is a name, not a geocoded street. */
        boolean org;

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
        final boolean org;

        Route(String id, long ms, double lat, double lon, String title, String description, boolean org) {
            this.id = id;
            this.ms = ms;
            this.lat = lat;
            this.lon = lon;
            this.title = title;
            this.description = description;
            this.org = org;
        }

        String key() {
            return id + "|" + ms;
        }

        /**
         * As a drive. An organisation's title is a name ({@link Trip#naviName}); a point's title
         * is only the Navigator's geocoded street, so it is the drive's address.
         */
        Trip trip() {
            Trip t = new Trip(0, ms, lat, lon, ms, lat, lon, null, org ? description : title, true);
            return (org && !title.isEmpty() ? t.withNaviName(title) : t).withDest(lat, lon);
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
            Object uri = field(v, from, to, "uri");
            p.org = uri instanceof String && !((String) uri).trim().isEmpty();
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
        return routes(deltas, others, null);
    }

    /**
     * @param guidanceStarts when the Navigator started leading a route ({@link NaviListener}),
     *                       or null when that is not known (no notification access): then a
     *                       third delta stands for «Поехали»
     */
    /**
     * Where the Navigator was sent between {@code fromMs} and {@code toMs} (a record used then, by
     * the on-unit deltas), the first — a later one may be the next row chosen; null if none. After
     * a route by Alice this is Yandex's own point for the place she found — kept for that row, for
     * a route without the network.
     */
    static double[] builtBetween(List<byte[]> deltas, List<byte[]> others, long fromMs, long toMs) {
        Map<String, Point> known = new LinkedHashMap<>();
        Map<String, Long> used = new HashMap<>();
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
                if (delta && p.lastUsed >= fromMs && p.lastUsed <= toMs) used.merge(p.id, p.lastUsed, Math::min);
            }
        }
        Point best = null;
        long bestMs = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : used.entrySet()) {
            Point k = known.get(e.getKey());
            if (k != null && place(k.lat, k.lon) && e.getValue() < bestMs) {
                best = k;
                bestMs = e.getValue();
            }
        }
        return best == null ? null : new double[]{best.lat, best.lon};
    }

    static List<Route> routes(List<byte[]> deltas, List<byte[]> others, List<Long> guidanceStarts) {
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
                if (p.org) k.org = true;
                if (delta && p.lastUsed > 0) times.computeIfAbsent(p.id, x -> new ArrayList<>()).add(p.lastUsed);
            }
        }
        List<Route> out = new ArrayList<>();
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (Point k : known.values()) {
            List<Long> ts = times.get(k.id);
            if (ts == null || !place(k.lat, k.lon)) continue;
            ts.sort(null);
            for (long ms : drives(ts, guidanceStarts, used)) {
                out.add(new Route(k.id, ms, k.lat, k.lon, k.title, k.description, k.org));
            }
        }
        out.sort((a, b) -> Long.compare(a.ms, b.ms));
        return out;
    }

    /**
     * The times a place was driven to, out of its delta times (sorted). Deltas come in bursts
     * (gaps of {@link #BURST_MS} at most): building writes two, «Поехали» by hand one more seconds
     * later, «Поехали» pressed by AutoGoService lands in the building's burst. A burst is a drive
     * when the Navigator started leading near it (−5…+10 s; each start counts once) — or, without
     * that signal, when it has 3+ deltas or is a lone delta within {@link #SAME_ROUTE_MS} of a
     * building. A route only looked at is no drive. The drive's time is its burst's end.
     */
    static List<Long> drives(List<Long> ts, List<Long> guidanceStarts, java.util.Set<Long> used) {
        List<long[]> bursts = new ArrayList<>(); // {start, end, count}
        for (long t : ts) {
            long[] last = bursts.isEmpty() ? null : bursts.get(bursts.size() - 1);
            if (last != null && t - last[1] <= BURST_MS) {
                last[1] = t;
                last[2]++;
            } else {
                bursts.add(new long[]{t, t, 1});
            }
        }
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < bursts.size(); i++) {
            long[] b = bursts.get(i);
            boolean go = false;
            if (guidanceStarts != null) {
                for (long g : guidanceStarts) {
                    if (!used.contains(g) && g >= b[0] - 5_000L && g <= b[1] + 10_000L) {
                        used.add(g);
                        go = true;
                        break;
                    }
                }
            } else {
                long[] prev = i > 0 ? bursts.get(i - 1) : null;
                go = b[2] >= 3 || (b[2] == 1 && prev != null && prev[2] >= 2 && b[0] - prev[1] <= SAME_ROUTE_MS);
            }
            if (go) out.add(b[1]);
        }
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
     * its destination — and then lends that drive its organisation's name and its address.
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
            } else {
                Trip g = out.get(same);
                String address = g.address == null || g.address.isEmpty() ? r.address : g.address;
                Trip named = g.withLabel(g.name, address, true);
                if (r.naviName != null && g.naviName == null) named = named.withNaviName(r.naviName);
                out.set(same, named.withDest(r.endLat, r.endLon));
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
                    .put("t", r.title).put("d", r.description).put("org", r.org));
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
                        o.optString("t"), o.optString("d"), o.optBoolean("org"));
                // v0.4.0 kept unparsed routes (no id, coordinates ~0): drop them
                if (!r.id.isEmpty() && place(r.lat, r.lon)) out.add(r);
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }
}
