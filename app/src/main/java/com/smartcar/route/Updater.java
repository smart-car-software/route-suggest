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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from the app itself, by the button only (the user's choice): the latest GitHub
 * Release of the (public) repository, its APK downloaded and installed — silently as root
 * through the unit's adb when it trusts us (as for the Navigator's history), else by Android's
 * installer, which asks. The same signing key, so the data stays.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/smart-car-software/route-suggest/releases/latest";

    static final class Release {
        final String tag;
        final String apkUrl;
        final String notes;

        Release(String tag, String apkUrl, String notes) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    private Updater() {
    }

    /** «v0.8.1» → {0, 8, 1}; anything else → null. Pure. */
    static int[] version(String v) {
        if (v == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(v.trim());
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
    }

    /** Whether {@code latest} is newer than {@code current}. Pure. */
    static boolean newer(String latest, String current) {
        int[] a = version(latest), b = version(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    /** GitHub's «latest release» answer → the release with its APK, or null. Pure. */
    static Release parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "");
        JSONArray assets = o.optJSONArray("assets");
        if (tag.isEmpty() || assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) {
                return new Release(tag, a.optString("browser_download_url"), o.optString("body", ""));
            }
        }
        return null;
    }

    /** The latest release, or null when there is none to be read. Blocking. */
    static Release latest() throws IOException {
        Http.Response r = Http.call("GET", LATEST, Http.headers("Accept", "application/vnd.github+json",
                "User-Agent", "route-suggest/" + BuildConfig.VERSION_NAME), null, null, null);
        if (r.code == 404) throw new IOException("релизы не видны (репозиторий закрыт?)");
        if (!r.ok()) throw new IOException("GitHub: HTTP " + r.code);
        try {
            return parse(r.body);
        } catch (JSONException e) {
            throw new IOException("GitHub ответил не так");
        }
    }

    /** Downloads the APK into the app's cache. Blocking. */
    static File download(Context c, Release rel) throws IOException {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection h = (HttpURLConnection) new URL(rel.apkUrl).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(Http.CONNECT_MS);
        h.setReadTimeout(60_000);
        h.setRequestProperty("User-Agent", "route-suggest/" + BuildConfig.VERSION_NAME);
        try (InputStream in = h.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            h.disconnect();
        }
        if (out.length() < 100_000) throw new IOException("скачался не APK");
        return out;
    }

    /**
     * As root through the unit's adb, silent. The app's process goes with the install; the
     * service comes back by MY_PACKAGE_REPLACED. Blocking; false when the unit does not trust us.
     */
    static boolean installAsRoot(Context c, File apk) throws IOException {
        if (!NaviReader.enabled(c) && !RouteService.prefs(c).getBoolean(AutoGoService.PREF_ROOT, false)) {
            return false;
        }
        String tmp = "/data/local/tmp/route-suggest-update.apk";
        StringBuilder out = new StringBuilder();
        // One command, waited for: a detached install did not survive this adb session (emulator).
        // The system ends this process only once the install is done, so «Success» may never
        // reach us — then the update is in, and the journal says so from the new version.
        Journal.add(c, "обновление: ставлю через root");
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("su 0 sh -c 'cp " + apk.getAbsolutePath() + " " + tmp + " && chmod 644 " + tmp
                    + " && echo COPIED && pm install -r " + tmp + "; rm -f " + tmp + "'", out::append);
        }
        String o = out.toString();
        if (!o.contains("COPIED")) throw new IOException("нет root: " + o.trim());
        if (!o.contains("Success")) {
            String err = o.substring(o.indexOf("COPIED") + 6).trim();
            throw new IOException("не установилось: " + (err.isEmpty() ? "?" : err));
        }
        return true;
    }

    /** Android's own installer: it asks the driver to confirm. */
    static void installWithPrompt(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("route-suggest.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            Intent done = new Intent(c, UpdateReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }
}
