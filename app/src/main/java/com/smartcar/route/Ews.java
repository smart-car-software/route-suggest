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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-premise Exchange through EWS (Exchange Web Services) with the work e-mail and password:
 * NTLM when the server offers it (it usually does), otherwise Basic over TLS. The server is
 * found from the address (exchange./mail./owa./autodiscover. of its domain) unless given. One
 * request reads the main calendar's occurrences of a window (CalendarView expands recurrences).
 * Microsoft 365 / Outlook.com no longer take passwords — not for them.
 */
final class Ews {
    static final String T = "http://schemas.microsoft.com/exchange/services/2006/types";
    static final String M = "http://schemas.microsoft.com/exchange/services/2006/messages";

    private final String email;
    private final String user;
    private final String domain;
    private final String password;

    /**
     * @param login {@code user@domain} (the UPN, as Exchange takes it) or {@code DOMAIN\\user}
     */
    Ews(String login, String password) {
        String l = login.trim();
        int bs = l.indexOf('\\');
        if (bs > 0) {
            domain = l.substring(0, bs);
            user = l.substring(bs + 1);
            email = "";
        } else {
            domain = "";
            user = l;
            email = l.contains("@") ? l : "";
        }
        this.password = password;
    }

    /** Where to look for EWS: the server given, else the usual names of the mail's domain. */
    static List<String> candidates(String server, String email) {
        List<String> out = new ArrayList<>();
        if (server != null && !server.trim().isEmpty()) {
            String s = server.trim();
            if (!s.startsWith("http")) s = "https://" + s;
            s = s.replaceAll("/+$", "");
            if (!s.toLowerCase(Locale.ROOT).endsWith(".asmx")) {
                s = s.replaceAll("(?i)/(owa|ews)$", "") + "/EWS/Exchange.asmx";
            }
            out.add(s);
            return out;
        }
        int at = email.indexOf('@');
        if (at < 0) return out;
        String d = email.substring(at + 1).toLowerCase(Locale.ROOT);
        for (String h : new String[]{"exchange.", "mail.", "owa.", "outlook.", "autodiscover.", ""}) {
            out.add("https://" + h + d + "/EWS/Exchange.asmx");
        }
        return out;
    }

    /** The first candidate that answers EWS with these credentials. */
    String find(String server) throws IOException {
        IOException last = null;
        CalDav.AuthException auth = null;
        for (String url : candidates(server, email)) {
            try {
                long now = System.currentTimeMillis();
                find(url, now, now + 60_000L);
                return url;
            } catch (CalDav.AuthException e) {
                auth = e; // maybe a wrong host that wants a login for anything: try the others
            } catch (IOException e) {
                last = e;
            }
        }
        if (auth != null) throw auth;
        throw last != null ? new IOException("сервер Exchange не найден — укажите адрес почты (OWA)")
                : new IOException("нужен адрес почты user@company.ru");
    }

    static final class Item {
        final String subject;
        final long startMs;
        final long endMs;
        final String location;
        final boolean allDay;
        final String freeBusy;
        final boolean cancelled;

        Item(String subject, long startMs, long endMs, String location, boolean allDay,
             String freeBusy, boolean cancelled) {
            this.subject = subject;
            this.startMs = startMs;
            this.endMs = endMs;
            this.location = location;
            this.allDay = allDay;
            this.freeBusy = freeBusy;
            this.cancelled = cancelled;
        }
    }

    List<Item> find(String url, long fromMs, long toMs) throws IOException {
        byte[] body = request(fromMs, toMs).getBytes(StandardCharsets.UTF_8);
        URL u = new URL(url);
        try (RawHttp c = new RawHttp(u)) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "text/xml; charset=utf-8");
            h.put("User-Agent", "route-suggest");
            // Ask with NTLM's first message straight away: an empty body, the same connection.
            Map<String, String> h1 = new LinkedHashMap<>(h);
            h1.put("Authorization", "NTLM " + Ntlm.negotiate());
            RawHttp.Response r = c.send("POST", RawHttp.target(u), h1, new byte[0]);
            Ntlm.Challenge ch = null;
            boolean basic = false, negotiate = false;
            for (String w : r.all("WWW-Authenticate")) {
                if (ch == null) ch = Ntlm.challenge(w);
                if (w.regionMatches(true, 0, "Basic", 0, 5)) basic = true;
                if (w.trim().equalsIgnoreCase("Negotiate")) negotiate = true;
            }
            String scheme = "NTLM ";
            if (ch == null && negotiate) {
                // Only «Negotiate» (SPNEGO): it takes a bare NTLM message as well.
                scheme = "Negotiate ";
                h1.put("Authorization", scheme + Ntlm.negotiate());
                r = c.send("POST", RawHttp.target(u), h1, new byte[0]);
                for (String w : r.all("WWW-Authenticate")) if (ch == null) ch = Ntlm.challenge(w);
            }
            if (ch != null) {
                Map<String, String> h3 = new LinkedHashMap<>(h);
                h3.put("Authorization", scheme + Ntlm.authenticate(ch, user, domain, password));
                r = c.send("POST", RawHttp.target(u), h3, body);
            } else if (basic || r.code == 401) {
                Map<String, String> hb = new LinkedHashMap<>(h);
                String who = domain.isEmpty() ? user : domain + "\\" + user;
                hb.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (who + ":" + password).getBytes(StandardCharsets.UTF_8)));
                r = c.send("POST", RawHttp.target(u), hb, body);
            } else if (r.code == 404) {
                throw new IOException("нет EWS по адресу " + u.getHost());
            }
            if (r.code == 401 || r.code == 403) throw new CalDav.AuthException("неверный логин или пароль");
            if (r.code != 200 && r.code != 500) throw new IOException("Exchange: HTTP " + r.code);
            return items(r.body);
        }
    }

    static String request(long fromMs, long toMs) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:t=\"" + T + "\" xmlns:m=\"" + M + "\">"
                + "<soap:Header><t:RequestServerVersion Version=\"Exchange2010_SP2\"/></soap:Header>"
                + "<soap:Body><m:FindItem Traversal=\"Shallow\"><m:ItemShape>"
                + "<t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>"
                + "<t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"calendar:Start\"/>"
                + "<t:FieldURI FieldURI=\"calendar:End\"/><t:FieldURI FieldURI=\"calendar:Location\"/>"
                + "<t:FieldURI FieldURI=\"calendar:IsAllDayEvent\"/>"
                + "<t:FieldURI FieldURI=\"calendar:LegacyFreeBusyStatus\"/>"
                + "<t:FieldURI FieldURI=\"calendar:IsCancelled\"/>"
                + "</t:AdditionalProperties></m:ItemShape>"
                + "<m:CalendarView MaxEntriesReturned=\"200\" StartDate=\"" + Instant.ofEpochMilli(fromMs)
                + "\" EndDate=\"" + Instant.ofEpochMilli(toMs) + "\"/>"
                + "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds>"
                + "</m:FindItem></soap:Body></soap:Envelope>";
    }

    /** CalendarItems of a FindItem answer; an EWS error (ResponseClass="Error") is thrown. */
    static List<Item> items(byte[] xml) throws IOException {
        Document d = CalDav.xml(xml);
        NodeList msgs = d.getElementsByTagNameNS(M, "FindItemResponseMessage");
        if (msgs.getLength() == 0) {
            NodeList faults = d.getElementsByTagNameNS("*", "faultstring");
            throw new IOException("Exchange: " + (faults.getLength() > 0
                    ? faults.item(0).getTextContent().trim() : "непонятный ответ"));
        }
        Element msg = (Element) msgs.item(0);
        if ("Error".equals(msg.getAttribute("ResponseClass"))) {
            NodeList t = msg.getElementsByTagNameNS(M, "MessageText");
            throw new IOException("Exchange: " + (t.getLength() > 0 ? t.item(0).getTextContent().trim() : "ошибка"));
        }
        List<Item> out = new ArrayList<>();
        NodeList items = d.getElementsByTagNameNS(T, "CalendarItem");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            try {
                out.add(new Item(text(e, "Subject"), Instant.parse(text(e, "Start")).toEpochMilli(),
                        Instant.parse(text(e, "End")).toEpochMilli(), text(e, "Location"),
                        "true".equals(text(e, "IsAllDayEvent")), text(e, "LegacyFreeBusyStatus"),
                        "true".equals(text(e, "IsCancelled"))));
            } catch (RuntimeException ignored) {
                // an item without times: not something to drive to
            }
        }
        return out;
    }

    private static String text(Element e, String name) {
        NodeList l = e.getElementsByTagNameNS(T, name);
        return l.getLength() > 0 ? l.item(0).getTextContent().trim() : "";
    }
}
