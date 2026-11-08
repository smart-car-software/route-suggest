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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * CalDAV (RFC 4791) with a login and a password (Basic over TLS): iCloud, Yandex, Mail.ru,
 * Nextcloud and the like. Finds the user's calendars from the server's address
 * (current-user-principal → calendar-home-set → the collections holding VEVENTs) and reads the
 * events of a window, asking the server to expand recurrences.
 */
final class CalDav {
    static final String DAV = "DAV:";
    static final String CAL = "urn:ietf:params:xml:ns:caldav";

    /** The login or the password is wrong (401/403). */
    static final class AuthException extends IOException {
        AuthException(String m) {
            super(m);
        }
    }

    static final class Calendar {
        final String url;
        final String name;

        Calendar(String url, String name) {
            this.url = url;
            this.name = name;
        }
    }

    private final String auth;
    private final Map<String, RawHttp> conns = new LinkedHashMap<>();

    CalDav(String login, String password) {
        auth = "Basic " + Base64.getEncoder().encodeToString(
                (login + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        for (RawHttp c : conns.values()) c.close();
        conns.clear();
    }

    /** A request, following redirects (iCloud sends each user to their own pNN host). */
    RawHttp.Response call(String method, String url, int depth, String body) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            String origin = u.getProtocol() + "://" + u.getHost() + ":" + u.getPort();
            RawHttp c = conns.get(origin);
            if (c == null) {
                c = new RawHttp(u);
                conns.put(origin, c);
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Authorization", auth);
            h.put("User-Agent", "route-suggest");
            if (depth >= 0) h.put("Depth", String.valueOf(depth));
            if (body != null) h.put("Content-Type", "application/xml; charset=utf-8");
            RawHttp.Response r = c.send(method, RawHttp.target(u), h,
                    body == null ? null : body.getBytes(StandardCharsets.UTF_8));
            String loc = r.header("Location");
            if (r.code >= 300 && r.code < 400 && loc != null) {
                url = new URL(u, loc).toString();
                continue;
            }
            if (r.code == 401 || r.code == 403) throw new AuthException("неверный логин или пароль");
            return new RawHttp.Response(r.code, r.headers, r.body, url);
        }
        throw new IOException("слишком много перенаправлений");
    }

    /** The calendars with events, found from the server's address. */
    List<Calendar> discover(String base) throws IOException {
        String principal = null;
        String home = null;
        String[] starts = {base, new URL(new URL(base), "/.well-known/caldav").toString()};
        for (String s : starts) {
            RawHttp.Response r = call("PROPFIND", s, 0, PROPS_PRINCIPAL);
            if (r.code != 207) continue;
            Document d = xml(r.body);
            principal = href(d, DAV, "current-user-principal", r.url);
            home = href(d, CAL, "calendar-home-set", r.url);
            if (principal != null || home != null) break;
        }
        if (home == null && principal != null) {
            RawHttp.Response r = call("PROPFIND", principal, 0, PROPS_HOME);
            if (r.code == 207) home = href(xml(r.body), CAL, "calendar-home-set", r.url);
        }
        if (home == null) throw new IOException("сервер не назвал календари пользователя");
        RawHttp.Response r = call("PROPFIND", home, 1, PROPS_LIST);
        if (r.code != 207) throw new IOException("список календарей: HTTP " + r.code);
        return calendars(r.body, r.url);
    }

    /** Events overlapping [fromMs, toMs) of one calendar. */
    List<ICal.VEvent> events(String calendarUrl, long fromMs, long toMs, ZoneId local)
            throws IOException {
        String range = "start=\"" + ICal.utc(fromMs) + "\" end=\"" + ICal.utc(toMs) + "\"";
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<C:calendar-query xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">"
                + "<D:prop><C:calendar-data><C:expand " + range + "/></C:calendar-data></D:prop>"
                + "<C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VEVENT\">"
                + "<C:time-range " + range + "/></C:comp-filter></C:comp-filter></C:filter>"
                + "</C:calendar-query>";
        RawHttp.Response r = call("REPORT", calendarUrl, 1, body);
        if (r.code != 207) throw new IOException("события: HTTP " + r.code);
        return eventsOf(r.body, local);
    }

    // ------------------------------------------------------------------ XML (pure)

    static final String PROPS_PRINCIPAL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><D:prop>"
            + "<D:current-user-principal/><C:calendar-home-set/></D:prop></D:propfind>";
    static final String PROPS_HOME = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><D:prop>"
            + "<C:calendar-home-set/></D:prop></D:propfind>";
    static final String PROPS_LIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\"><D:prop>"
            + "<D:resourcetype/><D:displayname/><C:supported-calendar-component-set/></D:prop></D:propfind>";

    static Document xml(byte[] body) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // not every parser knows it; the ones that don't fetch no DTDs anyway
            }
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new IOException("сервер ответил не XML");
        }
    }

    /** The href inside the first {ns}name element, resolved against the response's URL. */
    static String href(Document d, String ns, String name, String base) throws IOException {
        NodeList l = d.getElementsByTagNameNS(ns, name);
        for (int i = 0; i < l.getLength(); i++) {
            NodeList h = ((Element) l.item(i)).getElementsByTagNameNS(DAV, "href");
            if (h.getLength() > 0) {
                String v = h.item(0).getTextContent().trim();
                if (!v.isEmpty()) return new URL(new URL(base), v).toString();
            }
        }
        return null;
    }

    static List<Calendar> calendars(byte[] body, String base) throws IOException {
        List<Calendar> out = new ArrayList<>();
        Document d = xml(body);
        NodeList responses = d.getElementsByTagNameNS(DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            NodeList hrefs = resp.getElementsByTagNameNS(DAV, "href");
            if (hrefs.getLength() == 0) continue;
            Element ok = okProps(resp);
            if (ok == null) continue;
            if (ok.getElementsByTagNameNS(CAL, "calendar").getLength() == 0) continue; // not a calendar
            NodeList comps = ok.getElementsByTagNameNS(CAL, "comp");
            boolean events = comps.getLength() == 0;
            for (int j = 0; j < comps.getLength(); j++) {
                if ("VEVENT".equalsIgnoreCase(((Element) comps.item(j)).getAttribute("name"))) events = true;
            }
            if (!events) continue; // reminders (VTODO) only
            String url = new URL(new URL(base), hrefs.item(0).getTextContent().trim()).toString();
            NodeList names = ok.getElementsByTagNameNS(DAV, "displayname");
            String name = names.getLength() > 0 ? names.item(0).getTextContent().trim() : "";
            if (name.isEmpty()) {
                String p = new URL(url).getPath().replaceAll("/+$", "");
                name = p.substring(p.lastIndexOf('/') + 1);
            }
            out.add(new Calendar(url, name));
        }
        return out;
    }

    /** The prop of the propstat whose status is 200. */
    private static Element okProps(Element response) {
        NodeList ps = response.getElementsByTagNameNS(DAV, "propstat");
        for (int i = 0; i < ps.getLength(); i++) {
            Element p = (Element) ps.item(i);
            NodeList st = p.getElementsByTagNameNS(DAV, "status");
            String s = st.getLength() > 0 ? st.item(0).getTextContent() : "200";
            if (!s.contains(" 200")) continue;
            NodeList prop = p.getElementsByTagNameNS(DAV, "prop");
            if (prop.getLength() > 0) return (Element) prop.item(0);
        }
        return null;
    }

    static List<ICal.VEvent> eventsOf(byte[] body, ZoneId local) throws IOException {
        List<ICal.VEvent> out = new ArrayList<>();
        NodeList data = xml(body).getElementsByTagNameNS(CAL, "calendar-data");
        for (int i = 0; i < data.getLength(); i++) {
            Node n = data.item(i);
            out.addAll(ICal.parse(n.getTextContent(), local));
        }
        return out;
    }
}
