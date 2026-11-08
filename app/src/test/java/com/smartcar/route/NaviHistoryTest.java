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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Navigator's local «последние» (boost archives, as seen on the emulator with mod 30.3.0). */
public class NaviHistoryTest {
    static final class Archive {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();

        Archive raw(int... bytes) {
            for (int x : bytes) b.write(x);
            return this;
        }

        Archive u32(int v) {
            b.write(v);
            b.write(v >>> 8);
            b.write(v >>> 16);
            b.write(v >>> 24);
            return this;
        }

        Archive str(String s) {
            byte[] t = s.getBytes(StandardCharsets.UTF_8);
            u32(t.length);
            b.write(t, 0, t.length);
            return this;
        }

        Archive head() {
            return str("serialization::archive").raw(0x14, 0x00, 0x04, 0x04, 0x04, 0x08, 0x01, 0x00, 0x00, 0x00);
        }

        Archive record(String id) {
            return str("pointshistory").str(id);
        }

        Archive field(String name, int type) {
            return str(name).raw(1, 0, 0, 0, 1, type);
        }

        Archive time(String name, long ms) {
            field(name, 0x06).raw(0, 0, 0, 0, 0, 0, 0, 0);
            ByteBuffer x = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(ms);
            b.write(x.array(), 0, 8);
            return this;
        }

        Archive dbl(String name, double v) {
            field(name, 0x03);
            ByteBuffer x = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v);
            b.write(x.array(), 0, 8);
            return this;
        }

        Archive text(String name, String v) {
            return field(name, 0x04).str(v);
        }

        byte[] bytes() {
            return b.toByteArray();
        }
    }

    static byte[] delta(String id, long ms, double lat, double lon, String title) {
        Archive a = new Archive().head().record(id).text("description", "City of Kazan")
                .text("indoor_level", "").time("last_used", ms).dbl("latitude", lat).dbl("longitude", lon);
        if (title != null) a.text("title", title);
        return a.text("point_context", "").bytes();
    }

    static final long T = 1794161248629L; // 08.11 21:07:28.629 MSK — delta:17 on the emulator

    @Test
    public void readsARecord() {
        List<NaviHistory.Point> p = NaviHistory.records(delta("_JQPJWWB1RkSkZkyOsMeKGQc-Y5UMjoQ", T, 55.7512, 49.2101, "Pobedy Avenue"));
        assertEquals(1, p.size());
        assertEquals("_JQPJWWB1RkSkZkyOsMeKGQc-Y5UMjoQ", p.get(0).id);
        assertEquals(T, p.get(0).lastUsed);
        assertEquals(55.7512, p.get(0).lat, 1e-12);
        assertEquals(49.2101, p.get(0).lon, 1e-12);
        assertEquals("Pobedy Avenue", p.get(0).title);
        assertEquals("City of Kazan", p.get(0).description);
        assertTrue(NaviHistory.records("garbage".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    public void buildingAndGoAreOneRouteTheSnapshotGivesNoTimes() {
        String a = "_JQPJWWB1RkSkZkyOsMeKGQc-Y5UMjoQ", org = "ymapsbm1://org?oid=95813866834";
        List<byte[]> deltas = Arrays.asList(
                delta(a, T - 8_000L, 55.7512, 49.2101, null),     // building: two deltas
                delta(a, T - 8_000L, 55.7512, 49.2101, null),
                delta(a, T, 55.7512, 49.2101, null),              // «Go»
                delta(a, T + 86_400_000L, 55.7512, 49.2101, null), // the next day, again
                delta(org, T + 3_600_000L, 55.7988, 49.1063, "Kazan Kremlin"));
        // A snapshot from the account: the phone's route to somewhere else gives no drive, only
        // a name for a place the deltas know.
        byte[] snapshot = new Archive().head()
                .record("phone-only").time("last_used", T).dbl("latitude", 55.6).dbl("longitude", 49.3).text("title", "С телефона")
                .record(a).time("last_used", T + 5).dbl("latitude", 55.7512).dbl("longitude", 49.2101).text("title", "Pobedy Avenue")
                .bytes();
        List<NaviHistory.Route> r = NaviHistory.routes(deltas, Collections.singletonList(snapshot));
        assertEquals(3, r.size());
        assertEquals(T, r.get(0).ms);
        assertEquals("Pobedy Avenue", r.get(0).title); // named from the snapshot
        assertEquals("Kazan Kremlin", r.get(1).title);
        assertEquals(T + 86_400_000L, r.get(2).ms);
        for (NaviHistory.Route x : r) assertTrue(!x.title.equals("С телефона"));
        Trip t = r.get(1).trip();
        assertEquals(T + 3_600_000L, t.startMs);
        assertEquals(49.1063, t.endLon, 1e-12);
        assertEquals("Kazan Kremlin", t.name);
    }

    @Test
    public void keptAcrossSyncs() throws Exception {
        NaviHistory.Route old = new NaviHistory.Route("x", T, 55.7, 49.2, "A", "");
        NaviHistory.Route again = new NaviHistory.Route("x", T + 60_000L, 55.7, 49.2, "A", ""); // «Go» seen later
        NaviHistory.Route next = new NaviHistory.Route("y", T + 86_400_000L, 55.8, 49.1, "B", "");
        List<NaviHistory.Route> k = NaviHistory.accumulate(Collections.singletonList(old), Arrays.asList(again, next), 1000);
        assertEquals(2, k.size());
        List<NaviHistory.Route> capped = NaviHistory.accumulate(k, Collections.emptyList(), 1);
        assertEquals("y", capped.get(0).id);
        List<NaviHistory.Route> back = NaviHistory.fromJson(NaviHistory.toJson(k));
        assertEquals(2, back.size());
        assertEquals("B", back.get(1).title);
    }

    @Test
    public void routesAndRecordedDrivesCountOnce() {
        long t = SuggesterTest.at(11, 3, 18, 5);
        Trip recorded = new Trip(t + 5 * 60_000L, 55.8, 49.1, t + 25 * 60_000L, 55.7802, 49.1501);
        Trip same = new Trip(0, t, 55.78, 49.15, t, 55.78, 49.15, "Ice Power", "", true);
        Trip other = new Trip(0, t - 86_400_000L, 55.78, 49.15, t - 86_400_000L, 55.78, 49.15, "Ice Power", "", true);
        List<Trip> m = NaviHistory.merge(Collections.singletonList(recorded), Arrays.asList(same, other));
        assertEquals(2, m.size());
        assertEquals("Ice Power", m.get(0).name); // the recorded drive is named after the route
        assertEquals(t + 5 * 60_000L, m.get(0).startMs);
        assertEquals(new ArrayList<>(), NaviHistory.merge(new ArrayList<>(), new ArrayList<>()));
    }
}
