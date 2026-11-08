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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TripDetectorTest {
    final List<Trip> trips = new ArrayList<>();
    final TripDetector d = new TripDetector(trips::add);
    static final long MIN = 60_000L;

    /** Drives from (55.80, 49.10) east along a parallel, one fix every 10 s; 0.001° lon ≈ 62 m. */
    long drive(long t, double lon0, double lon1) {
        int steps = (int) Math.round(Math.abs(lon1 - lon0) / 0.001);
        for (int i = 1; i <= steps; i++) {
            t += 10_000L;
            d.onFix(55.80, lon0 + Math.signum(lon1 - lon0) * 0.001 * i, t);
        }
        return t;
    }

    long stand(long t, double lon, long ms) {
        for (long s = 0; s < ms; s += 10_000L) {
            t += 10_000L;
            d.onFix(55.80, lon, t);
        }
        return t;
    }

    @Test
    public void aDriveEndsAfterFiveMinutesStill() {
        long t = 0;
        t = stand(t, 49.10, 3 * MIN); // warming up at home
        long left = t;
        t = drive(t, 49.10, 49.15); // ~3 km, 50 fixes
        assertTrue(trips.isEmpty());
        t = stand(t, 49.15, 6 * MIN);
        assertEquals(1, trips.size());
        Trip trip = trips.get(0);
        // It left with the last fix within STILL_M of home: two 62 m steps out.
        assertEquals(left + 20_000L, trip.startMs);
        assertEquals(49.10, trip.startLon, 1e-9);
        assertEquals(49.15, trip.endLon, 1e-9);
        assertTrue(trip.endMs < t - 4 * MIN); // arrival, not the end of the wait
    }

    @Test
    public void theUnitGoingOffEndsTheDrive() {
        long t = stand(0, 49.10, MIN);
        t = drive(t, 49.10, 49.14);
        d.onFix(55.80, 49.14, t + 10_000L);
        // The engine is off: next fix 8 hours later, at the same place.
        d.onFix(55.80, 49.14, t + 8 * 60 * MIN);
        assertEquals(1, trips.size());
        assertEquals(49.14, trips.get(0).endLon, 1e-9);
        // …and a new drive starts from there.
        t = drive(t + 8 * 60 * MIN, 49.14, 49.10);
        d.endSession();
        assertEquals(2, trips.size());
        assertEquals(49.14, trips.get(1).startLon, 1e-9);
    }

    @Test
    public void roundTheBlockIsNoDrive() {
        long t = stand(0, 49.10, MIN);
        t = drive(t, 49.10, 49.105); // ~300 m
        t = stand(t, 49.105, 6 * MIN);
        assertTrue(trips.isEmpty());
        t = drive(t, 49.105, 49.10);
        d.endSession();
        assertTrue(trips.isEmpty());
    }

    @Test
    public void stateSurvivesTheProcess() {
        long t = stand(0, 49.10, MIN);
        t = drive(t, 49.10, 49.13);
        TripDetector again = new TripDetector(trips::add);
        again.restore(d.save());
        for (int i = 0; i < 40; i++) {
            t += 10_000L;
            again.onFix(55.80, 49.13, t);
        }
        assertEquals(1, trips.size());
        assertTrue(again.staleAt(t + 11 * MIN));
    }
}
