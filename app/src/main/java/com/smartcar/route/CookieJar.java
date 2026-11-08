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

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The Yandex session: cookies by name, domain and path, with absolute expiry, so they can be
 * kept in a file and sent by hand (HttpURLConnection's own cookie handling loses the ones set on
 * redirects, and the login is all redirects).
 */
final class CookieJar {
    static final class Cookie {
        final String name;
        final String value;
        final String domain;
        final boolean hostOnly;
        final String path;
        final long expiresMs;
        final boolean secure;

        Cookie(String name, String value, String domain, boolean hostOnly, String path,
               long expiresMs, boolean secure) {
            this.name = name;
            this.value = value;
            this.domain = domain.toLowerCase(Locale.ROOT);
            this.hostOnly = hostOnly;
            this.path = path == null || path.isEmpty() ? "/" : path;
            this.expiresMs = expiresMs;
            this.secure = secure;
        }

        boolean matches(String host, String path, boolean https, long nowMs) {
            if (expiresMs > 0 && expiresMs <= nowMs) return false;
            if (secure && !https) return false;
            host = host.toLowerCase(Locale.ROOT);
            boolean domainOk = hostOnly ? host.equals(domain)
                    : host.equals(domain) || host.endsWith("." + domain);
            return domainOk && (path.startsWith(this.path));
        }
    }

    private final List<Cookie> cookies = new ArrayList<>();

    synchronized void clear() {
        cookies.clear();
    }

    synchronized boolean has(String name) {
        for (Cookie c : cookies) if (c.name.equals(name)) return true;
        return false;
    }

    synchronized int size() {
        return cookies.size();
    }

    /** One Set-Cookie header of a response from host. */
    synchronized void store(String header, String host, long nowMs) {
        List<HttpCookie> parsed;
        try {
            parsed = HttpCookie.parse(header);
        } catch (IllegalArgumentException e) {
            return;
        }
        for (HttpCookie h : parsed) {
            String d = h.getDomain();
            boolean hostOnly = d == null || d.isEmpty();
            String domain = hostOnly ? host : (d.startsWith(".") ? d.substring(1) : d);
            if (!hostOnly && !(host.equalsIgnoreCase(domain) || host.toLowerCase(Locale.ROOT)
                    .endsWith("." + domain.toLowerCase(Locale.ROOT)))) {
                continue; // a cookie for somebody else's domain
            }
            long maxAge = h.getMaxAge();
            long expires = maxAge < 0 ? 0 : nowMs + maxAge * 1000L;
            Cookie c = new Cookie(h.getName(), h.getValue(), domain, hostOnly, h.getPath(), expires,
                    h.getSecure());
            remove(c.name, c.domain, c.path);
            if (maxAge != 0) cookies.add(c);
        }
    }

    private void remove(String name, String domain, String path) {
        for (Iterator<Cookie> it = cookies.iterator(); it.hasNext(); ) {
            Cookie c = it.next();
            if (c.name.equals(name) && c.domain.equals(domain) && c.path.equals(path)) it.remove();
        }
    }

    /** The Cookie header for a request, or null. */
    synchronized String header(String host, String path, boolean https, long nowMs) {
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (!c.matches(host, path == null || path.isEmpty() ? "/" : path, https, nowMs)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.name).append('=').append(c.value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    synchronized String save() {
        JSONArray a = new JSONArray();
        try {
            for (Cookie c : cookies) {
                a.put(new JSONObject().put("n", c.name).put("v", c.value).put("d", c.domain)
                        .put("h", c.hostOnly).put("p", c.path).put("e", c.expiresMs)
                        .put("s", c.secure));
            }
        } catch (JSONException e) {
            return "[]";
        }
        return a.toString();
    }

    synchronized void restore(String json) {
        cookies.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                cookies.add(new Cookie(o.getString("n"), o.getString("v"), o.getString("d"),
                        o.optBoolean("h"), o.optString("p", "/"), o.optLong("e"), o.optBoolean("s")));
            }
        } catch (JSONException e) {
            cookies.clear();
        }
    }
}
