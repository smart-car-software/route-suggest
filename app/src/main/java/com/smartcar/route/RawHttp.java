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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small HTTP/1.1 client on a socket of its own, for the calendars. Android's HttpURLConnection
 * refuses WebDAV methods (PROPFIND, REPORT) and has no NTLM, and NTLM needs its three messages
 * on one and the same connection — which this keeps open between {@link #send} calls.
 */
final class RawHttp implements Closeable {
    static final int CONNECT_MS = 8_000;
    static final int READ_MS = 20_000;
    static final int MAX_BODY = 8 * 1024 * 1024;

    static final class Response {
        final int code;
        final Map<String, List<String>> headers;
        final byte[] body;
        final String url;

        Response(int code, Map<String, List<String>> headers, byte[] body, String url) {
            this.code = code;
            this.headers = headers;
            this.body = body;
            this.url = url;
        }

        String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> all(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? new ArrayList<>() : v;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private final String scheme;
    private final String host;
    private final int port;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    RawHttp(URL url) {
        scheme = url.getProtocol().toLowerCase(Locale.ROOT);
        host = url.getHost();
        port = url.getPort() > 0 ? url.getPort() : ("https".equals(scheme) ? 443 : 80);
    }

    boolean sameOrigin(URL u) {
        int p = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80);
        return u.getProtocol().equalsIgnoreCase(scheme) && u.getHost().equalsIgnoreCase(host) && p == port;
    }

    private void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_MS);
        s.setSoTimeout(READ_MS);
        if ("https".equals(scheme)) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(s, host, port, true);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("сертификат не для " + host);
            }
            s = ssl;
        }
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /** One request on the kept connection (opened, or reopened once if the server closed it). */
    Response send(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            boolean fresh = socket == null;
            if (fresh) connect();
            try {
                return exchange(method, path, headers, body);
            } catch (IOException e) {
                close();
                if (fresh || attempt > 0) throw e; // a kept connection may have gone stale: once more
            }
        }
    }

    private Response exchange(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\n");
        boolean defaultPort = port == ("https".equals(scheme) ? 443 : 80);
        sb.append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) out.write(body);
        out.flush();

        String status = line();
        while (status.isEmpty()) status = line();
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("не HTTP: " + status);
        int code = Integer.parseInt(parts[1]);
        Map<String, List<String>> h = new LinkedHashMap<>();
        for (String l = line(); !l.isEmpty(); l = line()) {
            int c = l.indexOf(':');
            if (c <= 0) continue;
            h.computeIfAbsent(l.substring(0, c).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(l.substring(c + 1).trim());
        }
        byte[] data;
        String te = first(h, "transfer-encoding");
        String cl = first(h, "content-length");
        boolean close = "close".equalsIgnoreCase(first(h, "connection")) || parts[0].equals("HTTP/1.0");
        if ("HEAD".equals(method) || code == 204 || code == 304 || (code >= 100 && code < 200)) {
            data = new byte[0];
        } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            data = chunked();
        } else if (cl != null) {
            data = exactly(Integer.parseInt(cl.trim()));
        } else {
            data = toEnd();
            close = true;
        }
        if (close) close();
        String url = scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
        return new Response(code, h, data, url);
    }

    private static String first(Map<String, List<String>> h, String k) {
        List<String> v = h.get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 64 * 1024) throw new IOException("строка заголовка слишком длинная");
        }
        if (c == -1 && b.size() == 0) throw new IOException("соединение закрыто");
        return b.toString("ISO-8859-1");
    }

    private byte[] exactly(int n) throws IOException {
        if (n > MAX_BODY) throw new IOException("ответ слишком большой");
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("ответ оборвался");
            off += r;
        }
        return b;
    }

    private byte[] chunked() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            String size = line();
            int semi = size.indexOf(';');
            int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
            if (n == 0) {
                while (!line().isEmpty()) { /* trailers */ }
                return b.toByteArray();
            }
            b.write(exactly(n));
            if (b.size() > MAX_BODY) throw new IOException("ответ слишком большой");
            line();
        }
    }

    private byte[] toEnd() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            b.write(buf, 0, r);
            if (b.size() > MAX_BODY) throw new IOException("ответ слишком большой");
        }
        return b.toByteArray();
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    /** The path and query of a URL, as the request line wants it. */
    static String target(URL u) {
        String p = u.getPath().isEmpty() ? "/" : u.getPath();
        return u.getQuery() == null ? p : p + "?" + u.getQuery();
    }
}
