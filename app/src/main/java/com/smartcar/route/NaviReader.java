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

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Navigator's «последние» ({@link NaviHistory#DB}) as root: the database is the
 * Navigator's private file, so a copy comes through the unit's own adb daemon
 * ({@link LocalAdb}, as Car Notification Service gets the wheel) — {@code su 0 base64 <db>},
 * decoded into the app's cache and opened read-only. The Navigator's file is never touched.
 * The routes found are kept here: the deltas holding them go away once the Navigator syncs.
 *
 * <p>Off until the user turns it on in the app (the first connection makes the unit ask «allow
 * debugging?»). Blocking: call from a worker thread.
 */
final class NaviReader {
    static final String PREFS = "navi";
    static final int KEEP = 1000;

    private NaviReader() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("enabled", false);
    }

    /** The Navigator started leading a route: kept to tell a drive from a route looked at. */
    static void guidanceStarted(Context c, long ms) {
        List<Long> all = guidanceStarts(c);
        all.add(ms);
        while (all.size() > 300) all.remove(0);
        StringBuilder sb = new StringBuilder();
        for (long t : all) sb.append(sb.length() > 0 ? "," : "").append(t);
        prefs(c).edit().putString("guidance", sb.toString()).apply();
    }

    static List<Long> guidanceStarts(Context c) {
        List<Long> out = new ArrayList<>();
        for (String s : prefs(c).getString("guidance", "").split(",")) {
            try {
                if (!s.isEmpty()) out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static List<NaviHistory.Route> kept(Context c) {
        return NaviHistory.fromJson(prefs(c).getString("routes", ""));
    }

    /** Reads the Navigator's file and adds what is new; returns all the routes kept. */
    static synchronized List<NaviHistory.Route> read(Context c) throws IOException {
        byte[][] files = copy(c);
        File tmp = new File(c.getCacheDir(), "navi-points.sqlite");
        File journal = new File(c.getCacheDir(), "navi-points.sqlite-journal");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(files[0]);
        }
        //noinspection ResultOfMethodCallIgnored
        journal.delete();
        if (files[1].length > 0) {
            try (FileOutputStream out = new FileOutputStream(journal)) {
                out.write(files[1]);
            }
        }
        List<byte[]> deltas = new ArrayList<>(), others = new ArrayList<>();
        SQLiteDatabase sql = null;
        try {
            // Read-write on our own copy: a transaction the Navigator had not finished is rolled
            // back from the copied journal, as SQLite would do it itself.
            sql = SQLiteDatabase.openDatabase(tmp.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try (Cursor cur = sql.rawQuery("SELECT key, value FROM items", null)) {
                while (cur.moveToNext()) {
                    String key = cur.getString(0);
                    byte[] value = cur.getBlob(1);
                    if (value == null) continue;
                    if (key != null && key.startsWith("delta:")) deltas.add(value);
                    else others.add(value);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("не разобрал базу Навигатора: " + Errors.text(e));
        } finally {
            if (sql != null) sql.close();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            journal.delete();
        }
        List<NaviHistory.Route> all = NaviHistory.accumulate(kept(c),
                NaviHistory.routes(deltas, others, NaviListener.granted(c) ? guidanceStarts(c) : null), KEEP);
        try {
            prefs(c).edit().putString("routes", NaviHistory.toJson(all))
                    .putLong("read_at", System.currentTimeMillis()).putString("error", "").apply();
        } catch (JSONException e) {
            throw new IOException(e);
        }
        DebugLog.i("Navi", "history read: " + deltas.size() + " deltas, " + all.size() + " routes kept");
        return all;
    }

    private static final String SEPARATOR = "@@JOURNAL@@";

    /** {database, journal (maybe empty)}, both copied in one root command. */
    private static byte[][] copy(Context c) throws IOException {
        StringBuilder report = new StringBuilder();
        StringBuilder b64 = new StringBuilder();
        String db = NaviHistory.DB;
        try (LocalAdb adb = LocalAdb.connect(c, report)) {
            adb.shell("su 0 sh -c 'if test -r " + db + "; then base64 " + db + "; echo " + SEPARATOR
                    + "; base64 " + db + "-journal 2>/dev/null; else echo NOFILE; fi'", b64::append);
        }
        String text = b64.toString();
        if (text.contains("NOFILE")) throw new IOException("нет базы Навигатора (другая версия?)");
        int sep = text.indexOf(SEPARATOR);
        if (sep < 0) throw new IOException("нет root через adb: " + text.trim());
        try {
            byte[] main = Base64.decode(text.substring(0, sep).replaceAll("\\s", ""), Base64.DEFAULT);
            byte[] journal = Base64.decode(text.substring(sep + SEPARATOR.length()).replaceAll("\\s", ""),
                    Base64.DEFAULT);
            if (main.length < 100) throw new IOException("пустая база Навигатора");
            return new byte[][]{main, journal};
        } catch (IllegalArgumentException e) {
            throw new IOException("не прочитал базу Навигатора");
        }
    }
}
