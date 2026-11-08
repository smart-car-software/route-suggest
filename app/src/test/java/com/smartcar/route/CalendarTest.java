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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CalendarTest {
    static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    static long msk(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(MSK).toInstant().toEpochMilli();
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ NTLM

    @Test
    public void md4Vectors() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", hex(Ntlm.md4(new byte[0])));
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", hex(Ntlm.md4("abc".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("e33b4ddc9c38f2199c3e7b164fcc0536", hex(Ntlm.md4(
                "12345678901234567890123456789012345678901234567890123456789012345678901234567890"
                        .getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void ntlmv2KeysFromTheSpec() {
        // MS-NLMP 4.2.1 / 4.2.4: User, Domain, Password
        assertEquals("a4f49c406510bdcab6824ee7c30fd852", hex(Ntlm.md4(Ntlm.utf16("Password"))));
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hex(Ntlm.ntowfv2("User", "Domain", "Password")));
    }

    static String type2(byte[] challenge, byte[] info) {
        ByteBuffer b = ByteBuffer.allocate(48 + info.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII)).putInt(2);
        b.putShort((short) 0).putShort((short) 0).putInt(48); // target name
        b.putInt(Ntlm.FLAGS).put(challenge).putLong(0);
        b.putShort((short) info.length).putShort((short) info.length).putInt(48);
        b.put(info);
        return Base64.getEncoder().encodeToString(b.array());
    }

    @Test
    public void ntlmHandshakeMessages() {
        byte[] neg = Base64.getDecoder().decode(Ntlm.negotiate());
        assertEquals(32, neg.length);
        assertEquals(1, ByteBuffer.wrap(neg).order(ByteOrder.LITTLE_ENDIAN).getInt(8));

        byte[] info = {2, 0, 4, 0, 'D', 0, 'M', 0, 0, 0, 0, 0}; // MsvAvNbDomainName «DM», EOL
        byte[] server = {1, 2, 3, 4, 5, 6, 7, 8};
        Ntlm.Challenge c = Ntlm.challenge("NTLM " + type2(server, info));
        assertNotNull(c);
        assertTrue(Arrays.equals(server, c.serverChallenge));
        assertTrue(Arrays.equals(info, c.targetInfo));
        assertNotNull(Ntlm.challenge("Negotiate " + type2(server, info)));
        assertNull(Ntlm.challenge("Basic realm=\"x\""));
        assertNull(Ntlm.challenge("NTLM"));

        byte[] m = Base64.getDecoder().decode(Ntlm.authenticate(c, "user@corp.ru", "", "secret",
                new byte[8], Ntlm.filetime(0)));
        ByteBuffer b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(3, b.getInt(8));
        int ntLen = b.getShort(20), userLen = b.getShort(36), userOff = b.getInt(40);
        assertEquals(16 + 28 + info.length + 4, ntLen); // proof + blob
        assertEquals("user@corp.ru", new String(m, userOff, userLen, StandardCharsets.UTF_16LE));
        assertEquals(m.length, b.getInt(56)); // the (empty) session key's offset: the end
    }

    // ------------------------------------------------------------------ iCalendar

    static final String ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
            + "BEGIN:VTIMEZONE\r\nTZID:Europe/Moscow\r\nBEGIN:STANDARD\r\nDTSTART:19700101T000000\r\n"
            + "TZOFFSETFROM:+0300\r\nTZOFFSETTO:+0300\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
            + "BEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Ужин у Ильяса\\, с тортом\r\n"
            + "DTSTART;TZID=Europe/Moscow:20261110T190000\r\nDTEND;TZID=Europe/Moscow:20261110T210000\r\n"
            + "LOCATION:Ленина 5\\, Казань\r\nGEO:55.79;49.12\r\n"
            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nEND:VALARM\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:b\r\nSUMMARY:Врач\r\nDTSTART:20261110T150000Z\r\nDURATION:PT30M\r\n"
            + "X-APPLE-STRUCTURED-LOCATION;VALUE=URI;X-TITLE=\"Клиника: центр\":geo:55.8,4\r\n 9.1\r\n"
            + "END:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:c\r\nSUMMARY:Отпуск\r\nDTSTART;VALUE=DATE:20261110\r\n"
            + "DTEND;VALUE=DATE:20261112\r\nTRANSP:TRANSPARENT\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    @Test
    public void parsesEvents() {
        List<ICal.VEvent> e = ICal.parse(ICS, MSK);
        assertEquals(3, e.size());
        assertEquals("Ужин у Ильяса, с тортом", e.get(0).summary);
        assertEquals("Ленина 5, Казань", e.get(0).location);
        assertEquals(msk(11, 10, 19, 0), e.get(0).startMs);
        assertEquals(msk(11, 10, 21, 0), e.get(0).endMs);
        assertEquals(55.79, e.get(0).lat, 1e-9);
        assertEquals(msk(11, 10, 18, 0), e.get(1).startMs);
        assertEquals(msk(11, 10, 18, 30), e.get(1).endMs);
        assertEquals(49.1, e.get(1).lon, 1e-9); // folded line, quoted «:» in a parameter
        assertTrue(e.get(2).allDay);
        assertEquals("TRANSPARENT", e.get(2).transp);
        assertEquals(msk(11, 12, 0, 0), e.get(2).endMs);
    }

    static ICal.VEvent recurring(String dtstart, String rrule, String... extra) {
        StringBuilder sb = new StringBuilder("BEGIN:VEVENT\nUID:r\nSUMMARY:R\nDTSTART;TZID=Europe/Moscow:")
                .append(dtstart).append("\nDURATION:PT1H\nRRULE:").append(rrule).append('\n');
        for (String x : extra) sb.append(x).append('\n');
        sb.append("END:VEVENT\n");
        return ICal.parse(sb.toString(), MSK).get(0);
    }

    static List<Long> startsIn(List<ICal.VEvent> evs, long from, long to) {
        List<Long> out = new ArrayList<>();
        for (ICal.Occurrence o : ICal.occurrences(evs, from, to)) out.add(o.startMs);
        return out;
    }

    @Test
    public void weeklyAndMonthly() {
        // Every Tuesday and Thursday at 18:00 since September.
        ICal.VEvent w = recurring("20260901T180000", "FREQ=WEEKLY;BYDAY=TU,TH");
        assertEquals(Collections.singletonList(msk(11, 10, 18, 0)),
                startsIn(Collections.singletonList(w), msk(11, 10, 17, 0), msk(11, 10, 19, 0)));
        assertTrue(startsIn(Collections.singletonList(w), msk(11, 11, 0, 0), msk(11, 11, 23, 0)).isEmpty());
        // Every other week.
        ICal.VEvent w2 = recurring("20261103T180000", "FREQ=WEEKLY;INTERVAL=2");
        assertTrue(startsIn(Collections.singletonList(w2), msk(11, 10, 0, 0), msk(11, 11, 0, 0)).isEmpty());
        assertEquals(1, startsIn(Collections.singletonList(w2), msk(11, 17, 0, 0), msk(11, 18, 0, 0)).size());
        // The second Tuesday of the month; the last Friday.
        ICal.VEvent m = recurring("20260101T100000", "FREQ=MONTHLY;BYDAY=2TU");
        assertEquals(Collections.singletonList(msk(11, 10, 10, 0)),
                startsIn(Collections.singletonList(m), msk(11, 1, 0, 0), msk(11, 30, 0, 0)));
        ICal.VEvent last = recurring("20260101T100000", "FREQ=MONTHLY;BYDAY=-1FR");
        assertEquals(Collections.singletonList(msk(11, 27, 10, 0)),
                startsIn(Collections.singletonList(last), msk(11, 1, 0, 0), msk(11, 30, 0, 0)));
    }

    @Test
    public void limitsExceptionsAndMovedOccurrences() {
        ICal.VEvent counted = recurring("20261101T090000", "FREQ=DAILY;COUNT=5");
        assertEquals(5, startsIn(Collections.singletonList(counted), msk(10, 1, 0, 0), msk(12, 1, 0, 0)).size());
        ICal.VEvent until = recurring("20261101T090000", "FREQ=DAILY;UNTIL=20261103T060000Z");
        assertEquals(3, startsIn(Collections.singletonList(until), msk(10, 1, 0, 0), msk(12, 1, 0, 0)).size());
        // Daily since 2010: still found today.
        ICal.VEvent old = recurring("20100101T090000", "FREQ=DAILY");
        assertEquals(1, startsIn(Collections.singletonList(old), msk(11, 10, 0, 0), msk(11, 11, 0, 0)).size());
        // EXDATE, and an occurrence moved to 12:00.
        ICal.VEvent ex = recurring("20261101T090000", "FREQ=DAILY", "EXDATE;TZID=Europe/Moscow:20261110T090000");
        assertTrue(startsIn(Collections.singletonList(ex), msk(11, 10, 0, 0), msk(11, 11, 0, 0)).isEmpty());
        ICal.VEvent master = recurring("20261101T090000", "FREQ=DAILY");
        ICal.VEvent moved = ICal.parse("BEGIN:VEVENT\nUID:r\nSUMMARY:R\nRECURRENCE-ID;TZID=Europe/Moscow:20261110T090000\n"
                + "DTSTART;TZID=Europe/Moscow:20261110T120000\nDTEND;TZID=Europe/Moscow:20261110T130000\nEND:VEVENT\n", MSK).get(0);
        assertEquals(Collections.singletonList(msk(11, 10, 12, 0)),
                startsIn(Arrays.asList(master, moved), msk(11, 10, 0, 0), msk(11, 11, 0, 0)));
        // A Windows zone name.
        assertEquals(MSK, ICal.zone("Russian Standard Time", ZoneId.of("UTC")));
        assertEquals("20261110T150000Z", ICal.utc(msk(11, 10, 18, 0)));
    }

    // ------------------------------------------------------------------ CalDAV and EWS XML

    @Test
    public void caldavDiscoveryAnswers() throws Exception {
        String principal = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                + "<d:response><d:href>/</d:href><d:propstat><d:prop>"
                + "<d:current-user-principal><d:href>/123/principal/</d:href></d:current-user-principal>"
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        org.w3c.dom.Document d = CalDav.xml(principal.getBytes(StandardCharsets.UTF_8));
        assertEquals("https://caldav.icloud.com/123/principal/",
                CalDav.href(d, CalDav.DAV, "current-user-principal", "https://caldav.icloud.com/"));
        assertNull(CalDav.href(d, CalDav.CAL, "calendar-home-set", "https://caldav.icloud.com/"));

        String list = "<?xml version=\"1.0\"?><multistatus xmlns=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                + "<response><href>/123/calendars/</href><propstat><prop><resourcetype><collection/></resourcetype>"
                + "</prop><status>HTTP/1.1 200 OK</status></propstat></response>"
                + "<response><href>/123/calendars/home/</href><propstat><prop><resourcetype><collection/><C:calendar/></resourcetype>"
                + "<displayname>Дом</displayname><C:supported-calendar-component-set><C:comp name=\"VEVENT\"/></C:supported-calendar-component-set>"
                + "</prop><status>HTTP/1.1 200 OK</status></propstat></response>"
                + "<response><href>/123/calendars/tasks/</href><propstat><prop><resourcetype><collection/><C:calendar/></resourcetype>"
                + "<displayname>Напоминания</displayname><C:supported-calendar-component-set><C:comp name=\"VTODO\"/></C:supported-calendar-component-set>"
                + "</prop><status>HTTP/1.1 200 OK</status></propstat></response>"
                + "<response><href>/123/calendars/work/</href><propstat><prop><resourcetype><collection/><C:calendar/></resourcetype>"
                + "</prop><status>HTTP/1.1 200 OK</status></propstat><propstat><prop><displayname/></prop>"
                + "<status>HTTP/1.1 404 Not Found</status></propstat></response></multistatus>";
        List<CalDav.Calendar> cals = CalDav.calendars(list.getBytes(StandardCharsets.UTF_8), "https://p01-caldav.icloud.com/123/calendars/");
        assertEquals(2, cals.size());
        assertEquals("Дом", cals.get(0).name);
        assertEquals("https://p01-caldav.icloud.com/123/calendars/home/", cals.get(0).url);
        assertEquals("work", cals.get(1).name);

        String report = "<d:multistatus xmlns:d=\"DAV:\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\"><d:response>"
                + "<d:href>/e.ics</d:href><d:propstat><d:prop><cal:calendar-data>"
                + ICS.replace("&", "&amp;").replace("<", "&lt;")
                + "</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        assertEquals(3, CalDav.eventsOf(report.getBytes(StandardCharsets.UTF_8), MSK).size());
    }

    @Test
    public void ewsAnswers() throws Exception {
        String ok = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<m:FindItemResponse xmlns:m=\"" + Ews.M + "\" xmlns:t=\"" + Ews.T + "\"><m:ResponseMessages>"
                + "<m:FindItemResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode>"
                + "<m:RootFolder><t:Items>"
                + "<t:CalendarItem><t:Subject>Встреча у клиента</t:Subject><t:Start>2026-11-10T15:00:00Z</t:Start>"
                + "<t:End>2026-11-10T16:00:00Z</t:End><t:IsAllDayEvent>false</t:IsAllDayEvent>"
                + "<t:LegacyFreeBusyStatus>Busy</t:LegacyFreeBusyStatus><t:Location>ул. Баумана, 1</t:Location>"
                + "<t:IsCancelled>false</t:IsCancelled></t:CalendarItem>"
                + "<t:CalendarItem><t:Subject>Без времени</t:Subject></t:CalendarItem>"
                + "</t:Items></m:RootFolder></m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>"
                + "</s:Body></s:Envelope>";
        List<Ews.Item> items = Ews.items(ok.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, items.size());
        assertEquals("Встреча у клиента", items.get(0).subject);
        assertEquals(msk(11, 10, 18, 0), items.get(0).startMs);
        assertEquals("Busy", items.get(0).freeBusy);
        String err = ok.replace("ResponseClass=\"Success\"", "ResponseClass=\"Error\"")
                .replace("<m:ResponseCode>", "<m:MessageText>Нет ящика</m:MessageText><m:ResponseCode>");
        try {
            Ews.items(err.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("an EWS error must throw");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("Нет ящика"));
        }
        assertTrue(Ews.request(0, 1000).contains("CalendarView"));
        assertEquals(Arrays.asList("https://exchange.corp.ru/EWS/Exchange.asmx", "https://mail.corp.ru/EWS/Exchange.asmx",
                "https://owa.corp.ru/EWS/Exchange.asmx", "https://outlook.corp.ru/EWS/Exchange.asmx",
                "https://autodiscover.corp.ru/EWS/Exchange.asmx", "https://corp.ru/EWS/Exchange.asmx"),
                Ews.candidates("", "me@Corp.ru"));
        assertEquals(Collections.singletonList("https://mail.corp.ru/EWS/Exchange.asmx"),
                Ews.candidates("mail.corp.ru/owa/", "me@corp.ru"));
    }

    // ------------------------------------------------------------------ which events

    static EventFilter.Entry entry(String title, long start, long end, String location) {
        EventFilter.Entry e = new EventFilter.Entry();
        e.title = title;
        e.startMs = start;
        e.endMs = end;
        e.location = location;
        return e;
    }

    @Test
    public void eventsWorthARoute() {
        long now = msk(11, 10, 17, 45);
        List<Place> places = Arrays.asList(new Place(Place.Kind.HOME, "Дом", "Ленина 1", 55.8, 49.1),
                new Place(Place.Kind.BOOKMARK, "Ильяс", "", 55.79, 49.12));
        List<EventFilter.Entry> in = new ArrayList<>();
        in.add(entry("Ужин", msk(11, 10, 18, 0), msk(11, 10, 20, 0), "Ильяс"));          // a bookmark
        in.add(entry("Ужин", msk(11, 10, 18, 0), msk(11, 10, 20, 0), "Ильяс"));          // its copy
        in.add(entry("Врач", msk(11, 10, 18, 30), msk(11, 10, 19, 0), "Чистопольская 20"));
        in.add(entry("Созвон", msk(11, 10, 18, 0), msk(11, 10, 19, 0), "https://telemost.yandex.ru/j/1"));
        in.add(entry("Позже", msk(11, 10, 18, 50), msk(11, 10, 19, 0), "Чистопольская 20"));
        in.add(entry("Кончилось", msk(11, 10, 16, 0), msk(11, 10, 17, 0), "Чистопольская 20"));
        EventFilter.Entry room = entry("Планёрка", msk(11, 10, 18, 0), msk(11, 10, 19, 0), "Переговорка 5.12");
        room.work = true;
        in.add(room);
        EventFilter.Entry client = entry("У клиента", msk(11, 10, 18, 10), msk(11, 10, 19, 0), "ул. Баумана, 1");
        client.work = true;
        in.add(client);
        EventFilter.Entry free = entry("Свободно", msk(11, 10, 18, 0), msk(11, 10, 19, 0), "Ленина 1");
        free.free = true;
        in.add(free);
        EventFilter.Entry homeTitle = entry("Дом", msk(11, 10, 18, 20), msk(11, 10, 19, 0), "");
        in.add(homeTitle);

        List<CalEvent> out = EventFilter.filter(in, now, places);
        List<String> titles = new ArrayList<>();
        for (CalEvent e : out) titles.add(e.title);
        assertEquals(Arrays.asList("Ужин", "У клиента", "Дом", "Врач"), titles);
        assertEquals("Ильяс", out.get(0).place);
        assertTrue(out.get(0).hasGeo());
        assertFalse(out.get(1).hasGeo());
        assertEquals("Ленина 1", out.get(2).location);
        assertTrue(EventFilter.looksLikeAddress("Казань, Чистопольская, 20"));
        assertFalse(EventFilter.looksLikeAddress("Переговорка 5.12"));
        assertFalse(EventFilter.looksLikeAddress("Площадка у входа"));
        assertTrue(EventFilter.looksLikeAddress("пер. Островского 3"));
    }

    // ------------------------------------------------------------------ the socket client

    @Test
    public void keepsTheConnectionAndReadsChunks() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    accepted.incrementAndGet();
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    OutputStream o = s.getOutputStream();
                    for (int i = 0; i < 2; i++) {
                        String l;
                        int len = 0;
                        while (!(l = r.readLine()).isEmpty()) {
                            if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
                        }
                        for (int k = 0; k < len; k++) r.read();
                        if (i == 0) {
                            o.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: NTLM abc\r\n"
                                    + "WWW-Authenticate: Basic realm=\"x\"\r\nContent-Length: 2\r\n\r\nno").getBytes(StandardCharsets.ISO_8859_1));
                        } else {
                            o.write(("HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n"
                                    + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                        }
                        o.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            t.start();
            URL u = new URL("http://127.0.0.1:" + ss.getLocalPort() + "/dav/");
            try (RawHttp c = new RawHttp(u)) {
                RawHttp.Response a = c.send("PROPFIND", "/dav/", new LinkedHashMap<>(), "<x/>".getBytes(StandardCharsets.UTF_8));
                assertEquals(401, a.code);
                assertEquals(2, a.all("WWW-Authenticate").size());
                assertEquals("no", a.text());
                RawHttp.Response b = c.send("REPORT", "/dav/", new LinkedHashMap<>(), null);
                assertEquals(207, b.code);
                assertEquals("hello world", b.text());
            }
            t.join(5000);
            assertEquals(1, accepted.get());
        }
    }
}
