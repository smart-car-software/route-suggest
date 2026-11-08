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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Drives out of GPS fixes. A drive starts where the car stood before it moved off (its time: the
 * last fix there, i.e. when it left) and ends where it then stood still for
 * {@value #STOP_MS} ms, or at the last fix before the head unit went off or to sleep
 * ({@link #endSession}, also when fixes resume after a gap of {@value #GAP_MS} ms). Drives shorter
 * than {@value #MIN_TRIP_M} m (round the block, back to the start) are not drives.
 *
 * <p>Pure, with its state as JSON so that it survives the process and the unit's sleep.
 */
final class TripDetector {
    /** Within this of the anchor the car stands still. */
    static final double STILL_M = 150;
    static final long STOP_MS = 5 * 60_000L;
    static final long GAP_MS = 10 * 60_000L;
    static final double MIN_TRIP_M = 500;
    static final long MIN_TRIP_MS = 2 * 60_000L;

    interface Sink {
        void onTrip(Trip trip);
    }

    private final Sink sink;

    /** Where the drive starts; its time — the last moment the car was there. */
    private Fix start;
    /** Left the start area. */
    private boolean departed;
    /** Where the car has been standing (since its time) while out on a drive. */
    private Fix still;
    private Fix last;

    static final class Fix {
        final double lat;
        final double lon;
        final long ms;

        Fix(double lat, double lon, long ms) {
            this.lat = lat;
            this.lon = lon;
            this.ms = ms;
        }

        double meters(Fix o) {
            return Geo.meters(lat, lon, o.lat, o.lon);
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("lat", lat).put("lon", lon).put("ms", ms);
        }

        static Fix of(JSONObject o) {
            return o == null ? null : new Fix(o.optDouble("lat"), o.optDouble("lon"), o.optLong("ms"));
        }
    }

    TripDetector(Sink sink) {
        this.sink = sink;
    }

    Fix last() {
        return last;
    }

    /** True when the last fix is older than {@link #GAP_MS}: the car was off in between. */
    boolean staleAt(long nowMs) {
        return last != null && nowMs - last.ms > GAP_MS;
    }

    void onFix(double lat, double lon, long ms) {
        if (!Geo.valid(lat, lon)) return;
        Fix f = new Fix(lat, lon, ms);
        if (last != null && ms <= last.ms) return;
        if (last != null && ms - last.ms > GAP_MS) endSession();
        last = f;
        if (start == null) {
            start = f;
            departed = false;
            still = null;
            return;
        }
        if (!departed) {
            if (f.meters(start) <= STILL_M) {
                start = new Fix(start.lat, start.lon, ms); // still here: it leaves later
            } else {
                departed = true;
                still = f;
            }
            return;
        }
        if (still == null || f.meters(still) > STILL_M) {
            still = f;
            return;
        }
        if (ms - still.ms >= STOP_MS) {
            finish(new Fix(f.lat, f.lon, still.ms)); // where it stands, since when it stood
            start = new Fix(f.lat, f.lon, ms);
            departed = false;
            still = null;
        }
    }

    /** The unit is going off or to sleep, or was: the drive, if any, ended at the last fix. */
    void endSession() {
        if (departed && last != null) {
            boolean standing = still != null && last.meters(still) <= STILL_M;
            finish(standing ? new Fix(last.lat, last.lon, still.ms) : last);
        }
        start = null;
        departed = false;
        still = null;
    }

    private void finish(Fix end) {
        if (end.meters(start) >= MIN_TRIP_M && end.ms - start.ms >= MIN_TRIP_MS) {
            sink.onTrip(new Trip(start.ms, start.lat, start.lon, end.ms, end.lat, end.lon));
        }
    }

    String save() {
        try {
            JSONObject o = new JSONObject().put("departed", departed);
            if (start != null) o.put("start", start.json());
            if (still != null) o.put("still", still.json());
            if (last != null) o.put("last", last.json());
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    void restore(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            start = Fix.of(o.optJSONObject("start"));
            still = Fix.of(o.optJSONObject("still"));
            last = Fix.of(o.optJSONObject("last"));
            departed = o.optBoolean("departed") && start != null;
        } catch (JSONException e) {
            start = still = last = null;
            departed = false;
        }
    }
}
