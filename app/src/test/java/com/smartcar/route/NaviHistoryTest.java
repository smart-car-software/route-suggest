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

        int op = 2;

        /** The collection, the 22-byte service header seen in mod 30.3.0, the id. */
        Archive record(String id) {
            return str("pointshistory").raw(0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    .str(id);
        }

        Archive field(String name, int type) {
            return str(name).u32(op).raw(1).u32(type);
        }

        Archive time(String name, long ms) {
            field(name, 0x06).raw(0, 0, 0, 0, 0);
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
        return delta(id, ms, lat, lon, title, false);
    }

    static byte[] delta(String id, long ms, double lat, double lon, String title, boolean org) {
        Archive a = new Archive();
        a.op = title == null ? 1 : 2;
        a.head().record(id).text("description", "City of Kazan");
        a.field("indoor_level", 0x00);
        a.time("last_used", ms).dbl("latitude", lat).dbl("longitude", lon);
        if (title != null) a.text("title", title);
        if (org) a.text("uri", "ymapsbm1://org?oid=95813866834");
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
        String a = "_JQPJWWB1RkSkZkyOsMeKGQc-Y5UMjoQ", org = "_12KKIc.ZRLGaImLrgK7XaAKDswMX7OQ";
        long day = 86_400_000L, hour = 3_600_000L;
        List<byte[]> deltas = Arrays.asList(
                delta(a, T - 8_000L, 55.7512, 49.2101, null),     // building: two deltas
                delta(a, T - 8_000L, 55.7512, 49.2101, null),
                delta(a, T, 55.7512, 49.2101, null),              // «Go»
                delta(a, T + day - 20_000L, 55.7512, 49.2101, null), // the next day, again
                delta(a, T + day, 55.7512, 49.2101, null),
                delta(a, T + 2 * day, 55.7512, 49.2101, null),    // only looked at: no «Go»
                delta(a, T + 2 * day + 400, 55.7512, 49.2101, null),
                delta(org, T + hour - 10_000L, 55.7988, 49.1063, "Kazan Kremlin", true),
                delta(org, T + hour, 55.7988, 49.1063, "Kazan Kremlin", true));
        // A snapshot from the account: the phone's route to somewhere else gives no drive, only
        // a name for a place the deltas know.
        byte[] snapshot = new Archive().head()
                .record("_PHONEonlyPHONEonlyPHONEonly0001").time("last_used", T).dbl("latitude", 55.6).dbl("longitude", 49.3).text("title", "С телефона")
                .record(a).time("last_used", T + 5).dbl("latitude", 55.7512).dbl("longitude", 49.2101).text("title", "Pobedy Avenue")
                .bytes();
        List<NaviHistory.Route> r = NaviHistory.routes(deltas, Collections.singletonList(snapshot));
        assertEquals(3, r.size());
        assertEquals(T, r.get(0).ms);
        assertEquals("Pobedy Avenue", r.get(0).title); // named from the snapshot
        assertEquals("Kazan Kremlin", r.get(1).title);
        assertEquals(T + day, r.get(2).ms);
        for (NaviHistory.Route x : r) assertTrue(!x.title.equals("С телефона"));
        Trip t = r.get(1).trip();
        assertEquals(T + hour, t.startMs);
        assertEquals(49.1063, t.endLon, 1e-12);
        assertEquals(null, t.name);             // not a name the driver chose
        assertEquals("Kazan Kremlin", t.naviName); // an organisation: a name below bookmarks
        Trip street = r.get(0).trip();
        assertEquals(null, street.naviName);    // a point's title is the Navigator's geocoded street
        assertEquals("Pobedy Avenue", street.address);
    }

    // Real values from the emulator (Monjaro mod 30.3.0, test points): delta:15 — the record
    // created by build_route_on_map; delta:17 — «Go» on it; delta:20 — an organisation found by
    // search, with uri and a point_context.
    static final String D15 = "1600000073657269616C697A6174696F6E3A3A61726368697665140004040408010000000000000000000000000001000000020000000000000000000000000D000000706F696E7473686973746F727900000000000100000002000000000000000000000000200000005F4A51504A57574231526B536B5A6B794F734D654B4751632D5935554D6A6F510000000000010000000000000000080000000B0000000000000000000000000D000000706F696E745F636F6E7465787400010000000200000000010000000100000000000000000000000000000000000000000000000001000000090000006C6173745F75736564020000000106000000000000000071E857BCA1010000000000000001000000080000006C61746974756465020000000103000000AA60545227E04B40000000000001000000050000007469746C650200000001040000000D000000506F62656479204176656E75650000000000010000000C000000696E646F6F725F6C6576656C0200000001000000000000000000010000000B0000006465736372697074696F6E0200000001040000002400000043697479206F66204B617A616E2C2052657075626C6963206F662054617461727374616E00000000000100000015000000696E646F6F725F6C6576656C5F7375627469746C65020000000100000000000000000001000000090000006C6F6E676974756465020000000103000000DE718A8EE49A4840000000000001000000";
    static final String D17 = "1600000073657269616C697A6174696F6E3A3A61726368697665140004040408010000000000000000000000000001000000020000000000000000000000000D000000706F696E7473686973746F727900000000000100000002000000000000000000000000200000005F4A51504A57574231526B536B5A6B794F734D654B4751632D5935554D6A6F510000000000020000000000000000080000000B0000000000000000000000000D000000706F696E745F636F6E7465787400010000000100000000010000000100000000000000000000000000000000000000000000000001000000090000006C6173745F757365640100000001060000000000000000750958BCA1010000000000000001000000080000006C61746974756465010000000103000000AA60545227E04B40000000000001000000050000007469746C650100000001040000000D000000506F62656479204176656E75650000000000010000000C000000696E646F6F725F6C6576656C0100000001000000000000000000010000000B0000006465736372697074696F6E0100000001040000002400000043697479206F66204B617A616E2C2052657075626C6963206F662054617461727374616E00000000000100000015000000696E646F6F725F6C6576656C5F7375627469746C65010000000100000000000000000001000000090000006C6F6E676974756465010000000103000000DE718A8EE49A4840000000000001000000";
    static final String D20 = "1600000073657269616C697A6174696F6E3A3A61726368697665140004040408010000000000000000000000000001000000020000000000000000000000000D000000706F696E7473686973746F727900000000000100000002000000000000000000000000200000005F31324B4B49632E5A524C4761496D4C72674B375861414B4473774D58374F510000000000020000000000000000090000000B0000000000000000000000000D000000706F696E745F636F6E74657874000100000001000000000100000001000000000004000000B4000000646A4A384368514B45676B6D695F7550544931495142484A667175766A655A4C51416F554368494A5A7149497164754E53454152756D7165495F4C6C5330414B46416F5343652D4B594975326A5568414552756853494B67356B74414368514B45676C3043596665346F31495142467079654E702D65564C514249714368494A58335F5167756D4E53454152466D552D4F65336C5330415344546B314F44457A4F4459324F444D30587A416441414341507941680000000000000000000001000000090000006C6173745F757365640100000001060000000000000000ACF15BBCA1010000000000000001000000030000007572690100000001040000001E000000796D617073626D313A2F2F6F72673F6F69643D3935383133383636383334000000000001000000050000007469746C650100000001040000000D0000004B617A616E204B72656D6C696E000000000001000000080000006C6174697475646501000000010300000012D9075916E64B400000000000010000000C000000696E646F6F725F6C6576656C01000000010000000000000000000000000000010000000B0000006465736372697074696F6E0100000001040000003600000052657075626C6963206F662054617461727374616E2C2043697479206F66204B617A616E2C20536865796E6B6D616E6120447269766500000000000100000015000000696E646F6F725F6C6576656C5F7375627469746C65010000000100000000000000000001000000090000006C6F6E6769747564650100000001030000001D03B2D7BB8D4840000000000001000000";

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    public void realDeltasFromTheEmulator() {
        NaviHistory.Point a = NaviHistory.records(hex(D15)).get(0);
        assertEquals("_JQPJWWB1RkSkZkyOsMeKGQc-Y5UMjoQ", a.id);
        assertEquals(1794161240177L, a.lastUsed); // 21:07:20 MSK, building
        assertEquals(55.7512, a.lat, 1e-12);
        assertEquals(49.2101, a.lon, 1e-12);
        assertEquals("Pobedy Avenue", a.title);
        assertEquals("City of Kazan, Republic of Tatarstan", a.description);
        assertEquals(1794161248629L, NaviHistory.records(hex(D17)).get(0).lastUsed); // «Go»
        NaviHistory.Point k = NaviHistory.records(hex(D20)).get(0);
        assertEquals("_12KKIc.ZRLGaImLrgK7XaAKDswMX7OQ", k.id);
        assertEquals(1794161504684L, k.lastUsed);
        assertEquals("Kazan Kremlin", k.title);
        assertEquals(55.797557, k.lat, 1e-12);
        assertEquals(49.107295, k.lon, 1e-12);

        assertTrue(k.org);
        assertTrue(!a.org);

        List<NaviHistory.Route> r = NaviHistory.routes(Arrays.asList(hex(D15), hex(D17), hex(D20)),
                Collections.emptyList());
        // building + «Go» are one route; delta:20 alone (its building not given) is no «Go»
        assertEquals(1, r.size());
        assertEquals(1794161248629L, r.get(0).ms);
        assertEquals("Pobedy Avenue", r.get(0).title);
    }

    @Test
    public void keptAcrossSyncs() throws Exception {
        NaviHistory.Route old = new NaviHistory.Route("x", T, 55.7, 49.2, "A", "", false);
        NaviHistory.Route again = new NaviHistory.Route("x", T + 60_000L, 55.7, 49.2, "A", "", false); // «Go» seen later
        NaviHistory.Route next = new NaviHistory.Route("y", T + 86_400_000L, 55.8, 49.1, "B", "", true);
        List<NaviHistory.Route> k = NaviHistory.accumulate(Collections.singletonList(old), Arrays.asList(again, next), 1000);
        assertEquals(2, k.size());
        List<NaviHistory.Route> capped = NaviHistory.accumulate(k, Collections.emptyList(), 1);
        assertEquals("y", capped.get(0).id);
        List<NaviHistory.Route> back = NaviHistory.fromJson(NaviHistory.toJson(k));
        assertEquals(2, back.size());
        assertEquals("B", back.get(1).title);
        assertTrue(back.get(1).org);
    }

    @Test
    public void routesAndRecordedDrivesCountOnce() {
        long t = SuggesterTest.at(11, 3, 18, 5);
        Trip recorded = new Trip(t + 5 * 60_000L, 55.8, 49.1, t + 25 * 60_000L, 55.7802, 49.1501);
        Trip same = new Trip(0, t, 55.78, 49.15, t, 55.78, 49.15, null, "Баумана 1", true).withNaviName("Ice Power");
        Trip other = new Trip(0, t - 86_400_000L, 55.78, 49.15, t - 86_400_000L, 55.78, 49.15, null, "", true);
        List<Trip> m = NaviHistory.merge(Collections.singletonList(recorded), Arrays.asList(same, other));
        assertEquals(2, m.size());
        assertEquals("Ice Power", m.get(0).naviName); // the recorded drive takes the route's names
        assertEquals("Баумана 1", m.get(0).address);
        assertEquals(null, m.get(0).name);
        assertEquals(t + 5 * 60_000L, m.get(0).startMs);
        assertEquals(new ArrayList<>(), NaviHistory.merge(new ArrayList<>(), new ArrayList<>()));
    }
}
