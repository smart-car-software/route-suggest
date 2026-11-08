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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Starts the route in Yandex Navigator with its URL scheme
 * (yandex.ru/dev/navigator/doc/ru/concepts/navigator-url-params): {@code build_route_on_map}
 * to coordinates, from where the car stands; {@code map_search} for a destination known only by
 * its address or name. Unsigned URLs: Yandex's signature is for commercial use. Without the
 * Navigator — Yandex Maps, then any map app ({@code geo:}).
 */
final class Navigator {
    static final String NAVI = "ru.yandex.yandexnavi";
    static final String MAPS = "ru.yandex.yandexmaps";

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

    /** The URIs to try, best first. Pure. */
    static List<String[]> candidates(Suggestion s) {
        List<String[]> out = new ArrayList<>();
        if (s.hasGeo()) {
            String lat = String.format(Locale.ROOT, "%.6f", s.lat);
            String lon = String.format(Locale.ROOT, "%.6f", s.lon);
            out.add(new String[]{NAVI, "yandexnavi://build_route_on_map?lat_to=" + lat + "&lon_to=" + lon});
            out.add(new String[]{MAPS, "yandexmaps://maps.yandex.ru/?rtext=~" + lat + "," + lon + "&rtt=auto"});
            out.add(new String[]{null, "geo:" + lat + "," + lon + "?q=" + lat + "," + lon});
        } else if (!s.query.isEmpty()) {
            String q = Uri.encode(s.query);
            out.add(new String[]{NAVI, "yandexnavi://map_search?text=" + q});
            out.add(new String[]{MAPS, "yandexmaps://maps.yandex.ru/?text=" + q});
            out.add(new String[]{null, "geo:0,0?q=" + q});
        }
        return out;
    }

    static boolean open(Context c, Suggestion s) {
        for (String[] cand : candidates(s)) {
            if (cand[0] != null && !installed(c, cand[0])) continue;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(cand[1]));
            if (cand[0] != null) i.setPackage(cand[0]);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                DebugLog.i("Navigator", "route: " + cand[1].replaceAll("[-\\d.]{5,}", "…"));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                DebugLog.w("Navigator", "cannot open " + cand[0] + ": " + e);
            }
        }
        return false;
    }
}
