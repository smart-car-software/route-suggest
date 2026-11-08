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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Yandex account on the head unit: login with Yandex ID's «magic link» (the QR login of
 * passport.yandex.ru, the same as calendar-bot's) and the places read with that session.
 *
 * <p>Login: the passport page gives a CSRF token, «password/submit» opens a track, «magic/code»
 * gives the link. The app shows it as a QR; the user opens it on the phone and confirms in the
 * Yandex app; «magic/code/status» then says {@code otp_auth_finished} and «get_session» puts the
 * session cookies ({@code Session_id}) in the jar. No password, no OAuth client of Yandex's apps.
 * The cookies stay in the app's private files; «Выйти» deletes them.
 *
 * <p>Blocking: call from a worker thread.
 */
final class YandexAccount {
    static final String PASSPORT = "https://passport.yandex.ru/pwl-yandex";
    static final String UA = "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/130.0 Safari/537.36";
    private static final Pattern CSRF = Pattern.compile("__CSRF__ = \"([^\"]+)");

    /** The session is gone (never logged in, logged out, or Yandex ended it). */
    static final class LoginExpired extends IOException {
        LoginExpired(String m) {
            super(m);
        }
    }

    /** One login attempt: its own cookies until confirmed. */
    static final class Login {
        final CookieJar jar = new CookieJar();
        String csrf = "";
        JSONObject auth;
        String link = "";

        private Map<String, String> headers(boolean csrfHeader) {
            Map<String, String> h = Http.headers("User-Agent", UA, "Accept-Language", "ru");
            if (csrfHeader && !csrf.isEmpty()) h.put("X-CSRF-Token", csrf);
            return h;
        }

        private JSONObject json(Http.Response r) throws IOException {
            try {
                return new JSONObject(r.body);
            } catch (JSONException e) {
                throw new IOException("Яндекс ответил не JSON (HTTP " + r.code + ")");
            }
        }

        /** The link to confirm the login with. */
        String start() throws IOException {
            Http.Response page = Http.call("GET", PASSPORT, headers(false), null, null, jar);
            Matcher m = CSRF.matcher(page.body);
            if (!m.find()) throw new IOException("Яндекс ID не отдал страницу входа");
            csrf = m.group(1);
            auth = json(Http.call("POST", PASSPORT + "/api/passport/auth/password/submit",
                    headers(true), "{\"retpath\":\"https://passport.yandex.ru/\"}",
                    "application/json", jar));
            String track = auth.optString("track_id", "");
            if (track.isEmpty()) throw new IOException("Яндекс ID не начал вход");
            JSONObject res = json(Http.call("POST", PASSPORT + "/api/passport/auth/magic/code",
                    headers(true), Http.form("location_id", "0", "magic_track_id", track,
                            "track_id", ""), "application/x-www-form-urlencoded", jar));
            link = res.optString("link", "");
            if (link.isEmpty()) throw new IOException("Яндекс ID не дал ссылку для входа");
            return link;
        }

        /** Confirmed? Then the session cookies are in the jar. */
        boolean check() throws IOException {
            JSONObject res = json(Http.call("POST", PASSPORT + "/api/passport/auth/magic/code/status",
                    headers(true), auth.toString(), "application/json", jar));
            if (!"otp_auth_finished".equals(res.optString("state"))) return false;
            Http.call("POST", PASSPORT + "/api/passport/sessions/get_session", headers(true),
                    Http.form("track_id", res.optString("trackId", "")),
                    "application/x-www-form-urlencoded", jar);
            return jar.has("Session_id");
        }
    }

    private final File sessionFile;
    private final File placesFile;
    private final CookieJar jar = new CookieJar();
    private long syncedMs;
    private List<Place> places = new ArrayList<>();
    private String error = "";

    YandexAccount(File dir) {
        sessionFile = new File(dir, "yandex-session.json");
        placesFile = new File(dir, "yandex-places.json");
        jar.restore(readFile(sessionFile));
        try {
            JSONObject o = new JSONObject(readFile(placesFile));
            syncedMs = o.optLong("synced");
            places = YandexData.fromJson(o.optString("places"));
        } catch (JSONException e) {
            places = new ArrayList<>();
        }
    }

    synchronized boolean loggedIn() {
        return jar.has("Session_id");
    }

    synchronized List<Place> places() {
        return new ArrayList<>(places);
    }

    synchronized long syncedMs() {
        return syncedMs;
    }

    synchronized String error() {
        return error;
    }

    synchronized void saveLogin(Login login) throws IOException {
        jar.restore(login.jar.save());
        writeFile(sessionFile, jar.save());
        error = "";
    }

    synchronized void logout() {
        jar.clear();
        places = new ArrayList<>();
        syncedMs = 0;
        //noinspection ResultOfMethodCallIgnored
        sessionFile.delete();
        //noinspection ResultOfMethodCallIgnored
        placesFile.delete();
    }

    /** Reads «Дом», «Работа» and the bookmarks; keeps the last good set when Yandex fails. */
    void sync() throws IOException {
        if (!loggedIn()) throw new LoginExpired("нет входа");
        String addr = snapshot(YandexData.ADDRESSES);
        String marks = snapshot(YandexData.BOOKMARKS);
        List<Place> got = new ArrayList<>();
        try {
            got.addAll(YandexData.addresses(addr));
            got.addAll(YandexData.bookmarks(marks));
        } catch (JSONException e) {
            throw new IOException("Яндекс ответил не JSON");
        }
        synchronized (this) {
            places = got;
            syncedMs = System.currentTimeMillis();
            error = "";
            try {
                writeFile(placesFile, new JSONObject().put("synced", syncedMs)
                        .put("places", YandexData.toJson(places)).toString());
            } catch (JSONException e) {
                throw new IOException(e);
            }
            writeFile(sessionFile, jar.save()); // Yandex rotates the session: keep the new one
        }
    }

    synchronized void setError(String e) {
        error = e == null ? "" : e;
    }

    private String snapshot(String url) throws IOException {
        Http.Response r = Http.call("GET", url, Http.headers("User-Agent", UA,
                "Accept", "application/json", "Origin", "https://yandex.ru",
                "Referer", "https://yandex.ru/maps/"), null, null, jar);
        if (r.code == 401 || r.code == 403) {
            synchronized (this) {
                jar.clear();
                //noinspection ResultOfMethodCallIgnored
                sessionFile.delete();
                error = "Вход в Яндекс закончился — войдите снова";
            }
            throw new LoginExpired("HTTP " + r.code);
        }
        if (!r.ok()) throw new IOException("Яндекс: HTTP " + r.code);
        return r.body;
    }

    static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("не записал " + f.getName());
    }
}
