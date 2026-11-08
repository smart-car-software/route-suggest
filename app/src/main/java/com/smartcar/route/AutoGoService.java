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

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Presses «Поехали» in Yandex Navigator for the driver, so a route chosen with the wheel starts
 * leading without a touch: the URL scheme only builds the route and stops at its overview.
 *
 * <p>Armed only by {@link #arm} — when this app has just opened a route from «Куда едем?» —
 * for {@link #ARMED_MS}, and it presses once; anything else done in the Navigator is left
 * alone. It looks at the Navigator's windows only (see res/xml/autogo.xml). Off with the
 * switch «Сам нажимать «Поехали»» in the app.
 */
public final class AutoGoService extends AccessibilityService {
    static final long ARMED_MS = 25_000L;
    static final String PREF = "autogo";
    /** The button's text in the Russian and the English interface. */
    static final String[] LABELS = {"поехали", "go", "в путь"};

    private static volatile long armedUntil;

    /** A route was opened from our card: press «Поехали» if it comes up soon. */
    static void arm(Context c) {
        if (!on(c)) return;
        armedUntil = SystemClock.elapsedRealtime() + ARMED_MS;
    }

    /** The switch in the app (on unless turned off). */
    static boolean on(Context c) {
        return RouteService.prefs(c).getBoolean(PREF, true);
    }

    static boolean granted(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(new ComponentName(c, AutoGoService.class).flattenToString());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (SystemClock.elapsedRealtime() > armedUntil) return;
        if (event == null || event.getPackageName() == null
                || !Navigator.NAVI.contentEquals(event.getPackageName())) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo go = find(root);
        if (go == null) return;
        if (go.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0;
            DebugLog.i("AutoGo", "pressed «Поехали»");
        }
    }

    /** The «Поехали» button: a node with that very text (or description), or its clickable parent. */
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root) {
        for (String label : LABELS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (!matches(n.getText(), label) && !matches(n.getContentDescription(), label)) continue;
                for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
                    if (p.isClickable() && p.isEnabled() && p.isVisibleToUser()) return p;
                }
            }
        }
        return null;
    }

    /** The whole label, not a word in some longer text («Go around» is not «Go»). */
    static boolean matches(CharSequence text, String label) {
        return text != null && text.toString().trim().toLowerCase(Locale.ROOT).equals(label);
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Turns the service on as root (the unit's adb, as for the Navigator's history), adding it
     * to the services already on rather than replacing them. Blocking.
     */
    static void enableAsRoot(Context c) throws java.io.IOException {
        String me = new ComponentName(c, AutoGoService.class).flattenToString();
        String cmd = "s=$(settings get secure enabled_accessibility_services); case \"$s\" in *" + me
                + "*) ;; null|\"\") settings put secure enabled_accessibility_services " + me
                + ";; *) settings put secure enabled_accessibility_services \"$s:" + me
                + "\";; esac; settings put secure accessibility_enabled 1; echo DONE";
        StringBuilder out = new StringBuilder();
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("su 0 sh -c '" + cmd + "'", out::append);
        }
        if (!out.toString().contains("DONE")) throw new java.io.IOException("нет root: " + out.toString().trim());
    }
}
