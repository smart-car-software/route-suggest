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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the route in Yandex Navigator. Two ways, checked on the emulator (mod 30.3.0):
 * <ul>
 *   <li>a destination known by its name or address ({@link Suggestion#query}) goes to Alice,
 *       {@code ask_alice?text=Маршрут до … едем} — Yandex finds the place itself (an
 *       organisation's own point, not the middle of the house) and leads at once, no «Поехали»
 *       to press. Needs the network (Alice); offline, the coordinates or the search below;</li>
 *   <li>coordinates: {@code build_route_on_map} — the route overview, «Поехали» pressed by
 *       {@link AutoGoService}.</li>
 * </ul>
 * Not {@code rtext} with text (ignored) nor {@code map_search} (ends at a card or a list, the
 * route needs a finger — seen in the car), the latter only as the last resort. Never
 * coordinates to Alice: she reads them as longitude, latitude. Unsigned URLs: Yandex's signature
 * is for commercial use. Without the Navigator — Yandex Maps, then any map app ({@code geo:}).
 */
final class Navigator {
    static final String NAVI = "ru.yandex.yandexnavi";
    static final String MAPS = "ru.yandex.yandexmaps";

    private static final Pattern LAT_LON = Pattern.compile(
            "^\\s*(-?\\d{1,2}[.,]\\d+)\\s*[,; ]\\s*(-?\\d{1,3}[.,]\\d+)\\s*$");

    private Navigator() {
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** «55.79, 49.10» typed as a location: coordinates, not a text to search. */
    static double[] latLon(String text) {
        Matcher m = LAT_LON.matcher(text == null ? "" : text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1).replace(',', '.'));
        double lon = Double.parseDouble(m.group(2).replace(',', '.'));
        return Geo.valid(lat, lon) ? new double[]{lat, lon} : null;
    }

    /** Whether Alice will lead (no «Поехали» to press): a text to go by, and the network. */
    static boolean viaAlice(Suggestion s, boolean online) {
        return online && !s.query.trim().isEmpty() && latLon(s.query) == null;
    }

    /** The URIs to try, best first. Pure. */
    static List<String[]> candidates(Suggestion s, boolean online) {
        List<String[]> out = new ArrayList<>();
        double[] typed = latLon(s.query);
        double lat = s.hasGeo() ? s.lat : typed != null ? typed[0] : Double.NaN;
        double lon = s.hasGeo() ? s.lon : typed != null ? typed[1] : Double.NaN;
        if (viaAlice(s, online)) {
            out.add(new String[]{NAVI, "yandexnavi://ask_alice?text="
                    + encode("Маршрут до " + s.query.trim() + " едем")});
        }
        if (Geo.valid(lat, lon)) {
            String la = String.format(Locale.ROOT, "%.6f", lat);
            String lo = String.format(Locale.ROOT, "%.6f", lon);
            out.add(new String[]{NAVI, "yandexnavi://build_route_on_map?lat_to=" + la + "&lon_to=" + lo});
            out.add(new String[]{MAPS, "yandexmaps://maps.yandex.ru/?rtext=~" + la + "," + lo + "&rtt=auto"});
            out.add(new String[]{null, "geo:" + la + "," + lo + "?q=" + la + "," + lo});
        } else if (!s.query.trim().isEmpty()) {
            String q = encode(s.query.trim());
            out.add(new String[]{NAVI, "yandexnavi://map_search?text=" + q});
            out.add(new String[]{MAPS, "yandexmaps://maps.yandex.ru/?text=" + q});
            out.add(new String[]{null, "geo:0,0?q=" + q});
        }
        return out;
    }

    /** Percent-encoding as {@code Uri.encode} does it (spaces as %20), pure for the tests. */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int ch = b & 0xFF;
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '!' || ch == '*'
                    || ch == '\'' || ch == '(' || ch == ')') {
                sb.append((char) ch);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", ch));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation") // NetworkInfo: the plain answer on Android 9
    static boolean online(Context c) {
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    /** Alice on a Navigator that was not running lost the text once in two (emulator). */
    static final long WARM_UP_MS = 5_000L;

    static boolean open(Context c, Suggestion s) {
        List<String[]> all = candidates(s, online(c));
        if (!all.isEmpty() && all.get(0)[1].startsWith("yandexnavi://ask_alice") && installed(c, NAVI)) {
            String uri = all.get(0)[1];
            Context app = c.getApplicationContext();
            new Thread(() -> {
                if (!running(app)) {
                    Intent launch = app.getPackageManager().getLaunchIntentForPackage(NAVI);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            app.startActivity(launch);
                            Thread.sleep(WARM_UP_MS);
                        } catch (RuntimeException | InterruptedException e) {
                            DebugLog.w("Navigator", "warm-up: " + e);
                        }
                    }
                }
                start(app, NAVI, uri);
            }, "alice").start();
            return true;
        }
        return openFirst(c, all);
    }

    /**
     * Whether the Navigator's process is up: {@code pidof} through the unit's adb (root is not
     * needed for it, the adb connection is). Unknown counts as not running — a warm-up then.
     * Blocking.
     */
    private static boolean running(Context c) {
        // Only when the unit already trusts our adb key: else it would ask «allow debugging?».
        if (!NaviReader.enabled(c) && !RouteService.prefs(c).getBoolean(AutoGoService.PREF_ROOT, false)) {
            return false;
        }
        StringBuilder out = new StringBuilder();
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("pidof " + NAVI + " || echo NONE", out::append);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
        String o = out.toString().trim();
        return !o.isEmpty() && !o.contains("NONE") && o.matches("(?s)\\d+.*");
    }

    private static boolean start(Context c, String pkg, String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        if (pkg != null) i.setPackage(pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            DebugLog.i("Navigator", "route: " + uri.replaceAll("\\?.*", ""));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            DebugLog.w("Navigator", "cannot open " + pkg + ": " + e);
            return false;
        }
    }

    private static boolean openFirst(Context c, List<String[]> all) {
        for (String[] cand : all) {
            if (cand[0] != null && !installed(c, cand[0])) continue;
            if (start(c, cand[0], cand[1])) return true; // no address or coordinates in the log
        }
        return false;
    }
}
