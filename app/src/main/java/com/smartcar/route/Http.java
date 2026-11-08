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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small blocking HTTP: redirects followed by hand so that a {@link CookieJar} sees every hop. */
final class Http {
    static final int CONNECT_MS = 8_000;
    static final int READ_MS = 15_000;
    static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private Http() {
    }

    /**
     * @param body       null for GET
     * @param type       its Content-Type
     * @param jar        cookies to send and to keep, or null
     */
    static Response call(String method, String url, Map<String, String> headers, String body,
                         String type, CookieJar jar) throws IOException {
        for (int hop = 0; hop < 10; hop++) {
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setRequestMethod(method);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
                boolean https = "https".equals(u.getProtocol());
                String path = u.getPath().isEmpty() ? "/" : u.getPath();
                if (jar != null) {
                    String cookie = jar.header(u.getHost(), path, https, System.currentTimeMillis());
                    if (cookie != null) c.setRequestProperty("Cookie", cookie);
                }
                if (body != null) {
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", type);
                    c.setFixedLengthStreamingMode(b.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(b);
                    }
                }
                int code = c.getResponseCode();
                if (jar != null) {
                    for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                        if (h.getKey() == null || !"set-cookie".equalsIgnoreCase(h.getKey())) continue;
                        for (String v : h.getValue()) jar.store(v, u.getHost(), System.currentTimeMillis());
                    }
                }
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    url = new URL(u, loc).toString();
                    if (code != 307 && code != 308) {
                        method = "GET";
                        body = null;
                    }
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Response(code, in == null ? "" : read(in));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("response too big");
            }
            return out.toString("UTF-8");
        }
    }

    static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (sb.length() > 0) sb.append('&');
                sb.append(java.net.URLEncoder.encode(kv[i], "UTF-8")).append('=')
                        .append(java.net.URLEncoder.encode(kv[i + 1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }
}
