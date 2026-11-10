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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Куда едем?» in the cluster's own menu: on the navigation tab of the driver's display, a
 * list of every place, the most likely first ({@link Suggester#menu}); the left joystick of the
 * wheel scrolls it and its press starts the route.
 *
 * <p>How the unit lets an app in there (learned from how mNavi does it; no ECarX SDK is
 * public): ECarX's {@code DimMenuInteraction} (in the unit's framework, reached by reflection)
 * calls back {@code onTabChanged(tab)} when the driver switches the cluster menu's tab,
 * {@link #NAVI_TAB} being navigation; on that tab the joystick comes to Android as the broadcasts
 * {@link #KEY_UP} / {@link #KEY_DOWN} / {@link #KEY_OK}. The list itself is our overlay window on
 * the cluster display, put where the tab's panel is ({@link #X}, {@link #Y}).
 *
 * <p>Not shown while the Navigator leads a route (its map is what that tab is for then), and
 * gone once a row is chosen, until the tab is opened again. Off with the switch in the app.
 */
final class ClusterMenu {
    static final String PREF = "cluster_menu";

    static final String DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction";
    static final String DIM_CALLBACK =
            "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction$IDimMenuInteractionCallback";
    static final int NAVI_TAB = 2;
    static final String KEY_UP = "ecarx.intent.action.ECARX_KEY_DIMSCROLLUP_EVENT";
    static final String KEY_DOWN = "ecarx.intent.action.ECARX_KEY_DIMSCROLLDOWN_EVENT";
    static final String KEY_OK = "ecarx.intent.action.ECARX_KEY_DIMCONFIRM_EVENT";

    /** The driver's display and the navigation tab's panel on it, px. */
    static final int DISPLAY = 2;
    static final int X = 740;
    static final int Y = 720;
    static final int W = 414;
    static final int H = 284;
    static final int TITLE_H = 44;
    static final int VISIBLE_ROWS = 5;
    /** The test from the app shows the list for this long. */
    static final long TRIAL_MS = 30_000L;

    interface Rows {
        /** What to list now. Blocking: called on the menu's own thread. */
        List<Suggestion> rows();
    }

    private static final String TAG = "Menu";
    /** For the app's status: what the unit said, if anything. */
    static volatile String state = "";

    private final Context c;
    private final Rows source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Object dim;
    private Object callback;
    private boolean keysRegistered;

    private WindowManager wm;
    private LinearLayout view;
    private LinearLayout list;
    private List<Suggestion> rows = new ArrayList<>();
    private int selected;
    private int first;
    /** Bumped on each show and hide: a late list for an old show is dropped. */
    private int generation;

    ClusterMenu(Context c, Rows source) {
        this.c = c;
        this.source = source;
    }

    static boolean on(Context c) {
        return RouteService.prefs(c).getBoolean(PREF, true);
    }

    static boolean granted(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Overlay permission as root (the unit's adb): {@code appops}. Blocking. */
    static void grantAsRoot(Context c) throws java.io.IOException {
        StringBuilder out = new StringBuilder();
        try (LocalAdb adb = LocalAdb.connect(c, new StringBuilder())) {
            adb.shell("su 0 sh -c 'appops set " + c.getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow; echo DONE'", out::append);
        }
        if (!out.toString().contains("DONE")) {
            throw new java.io.IOException("нет root: " + out.toString().trim());
        }
    }

    // ------------------------------------------------------------------ the unit's side

    void start() {
        main.post(() -> {
            if (!keysRegistered) {
                IntentFilter f = new IntentFilter();
                f.addAction(KEY_UP);
                f.addAction(KEY_DOWN);
                f.addAction(KEY_OK);
                c.registerReceiver(keys, f);
                keysRegistered = true;
            }
            if (dim != null) return;
            try {
                Class<?> cls = Class.forName(DIM_CLASS);
                Class<?> cb = Class.forName(DIM_CALLBACK);
                dim = cls.getConstructor(Context.class).newInstance(c);
                callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb},
                        (proxy, m, args) -> {
                            // The unit keeps callbacks in lists: identity for these.
                            switch (m.getName()) {
                                case "equals":
                                    return args != null && args.length == 1 && proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "route-suggest DIM callback";
                                default:
                                    break;
                            }
                            onUnitCall(m, args);
                            return defaultValue(m.getReturnType());
                        });
                cls.getMethod("registerDimMenuInteractionCallback", cb).invoke(dim, callback);
                state = "подключено";
                DebugLog.i(TAG, "DIM menu callback registered");
            } catch (ClassNotFoundException e) {
                dim = null;
                state = "нет ECarX (не магнитола Monjaro?)";
                DebugLog.i(TAG, "no " + DIM_CLASS);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                dim = null;
                state = "ошибка: " + Errors.text(e instanceof java.lang.reflect.InvocationTargetException
                        && e.getCause() != null ? e.getCause() : e);
                DebugLog.w(TAG, "DIM menu: " + e);
            }
        });
    }

    void stop() {
        main.post(() -> {
            hide();
            if (keysRegistered) {
                try {
                    c.unregisterReceiver(keys);
                } catch (IllegalArgumentException ignored) {
                }
                keysRegistered = false;
            }
            if (dim != null && callback != null) {
                try {
                    Class<?> cb = Class.forName(DIM_CALLBACK);
                    dim.getClass().getMethod("unregisterDimMenuInteractionCallback", cb)
                            .invoke(dim, callback);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    DebugLog.w(TAG, "unregister: " + e);
                }
            }
            dim = null;
            callback = null;
        });
        worker.shutdownNow();
    }

    /** Any call from the unit, on whatever thread it comes. */
    private void onUnitCall(Method m, Object[] args) {
        String name = m.getName();
        if (m.getDeclaringClass() == Object.class) return;
        if ("onTabChanged".equals(name) && args != null && args.length == 1
                && args[0] instanceof Integer) {
            int tab = (Integer) args[0];
            DebugLog.i(TAG, "tab " + tab);
            state = "подключено, вкладка " + tab;
            main.post(() -> {
                if (tab == NAVI_TAB) show(0);
                else hide();
            });
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class || t == short.class || t == byte.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == char.class) return '\0';
        return null;
    }

    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String a = i.getAction();
            if (a == null || view == null) return;
            if (KEY_UP.equals(a)) move(-1);
            else if (KEY_DOWN.equals(a)) move(1);
            else if (KEY_OK.equals(a)) pick();
        }
    };

    // ------------------------------------------------------------------ the list

    /** From the app: the list for {@link #TRIAL_MS}, as on the tab (on the unit's display 2). */
    void trial() {
        main.post(() -> show(TRIAL_MS));
    }

    /** On the main thread. {@code forMs} > 0: hide by itself after that long. */
    private void show(long forMs) {
        if (!on(c)) return;
        if (forMs == 0 && NaviListener.guiding()) {
            DebugLog.i(TAG, "the Navigator leads a route: its map stays");
            return;
        }
        if (!granted(c)) {
            state = "нет разрешения показывать поверх окон";
            DebugLog.w(TAG, "no overlay permission");
            return;
        }
        int gen = ++generation;
        run(() -> {
            List<Suggestion> r;
            try {
                r = source.rows();
            } catch (RuntimeException e) {
                DebugLog.w(TAG, "rows: " + e);
                return;
            }
            main.post(() -> {
                if (gen != generation) return;
                rows = r;
                selected = 0;
                first = 0;
                if (!attach()) return;
                draw();
                if (forMs > 0) main.postDelayed(() -> {
                    if (gen == generation) hide();
                }, forMs);
            });
        });
    }

    /** New rows while the list is up (the calendar came later): the same row stays selected. */
    void update() {
        main.post(() -> {
            if (view == null) return;
            int gen = generation;
            run(() -> {
                List<Suggestion> r;
                try {
                    r = source.rows();
                } catch (RuntimeException e) {
                    DebugLog.w(TAG, "rows: " + e);
                    return;
                }
                main.post(() -> {
                    if (gen != generation || view == null) return;
                    String was = selected < rows.size() ? rows.get(selected).row : "";
                    rows = r;
                    selected = 0;
                    for (int n = 0; n < r.size(); n++) if (r.get(n).row.equals(was)) selected = n;
                    first = Math.max(0, Math.min(first, selected));
                    draw();
                });
            });
        });
    }

    /** The switch turned off: the list goes now; the subscription stays till the service ends. */
    void hideNow() {
        main.post(this::hide);
    }

    private void run(Runnable r) {
        try {
            worker.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // the service is going
        }
    }

    private void hide() {
        generation++;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
        view = null;
        list = null;
    }

    private void move(int by) {
        if (rows.isEmpty()) return;
        selected = Math.max(0, Math.min(rows.size() - 1, selected + by));
        if (selected < first) first = selected;
        if (selected >= first + VISIBLE_ROWS) first = selected - VISIBLE_ROWS + 1;
        draw();
    }

    private void pick() {
        if (selected >= rows.size()) return;
        Suggestion s = rows.get(selected);
        hide();
        DebugLog.i(TAG, "chosen: " + s.kind);
        RouteService.choose(c, s);
    }

    private boolean attach() {
        if (view != null) return true;
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(DISPLAY);
        boolean onCluster = d != null;
        if (d == null && dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY); // the emulator
        if (d == null) return false;
        Context dc = c.createDisplayContext(d);
        wm = dc.getSystemService(WindowManager.class);
        view = new LinearLayout(dc);
        view.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.getColor(R.color.cluster_panel));
        bg.setCornerRadius(12);
        bg.setStroke(1, c.getColor(R.color.cluster_panel_rim));
        view.setBackground(bg);
        TextView title = new TextView(dc);
        title.setText(R.string.card_title);
        title.setTextColor(c.getColor(R.color.cluster_text_dim));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(18, 0, 18, 0);
        view.addView(title, new LinearLayout.LayoutParams(W, TITLE_H));
        list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        view.addView(list, new LinearLayout.LayoutParams(W, H - TITLE_H));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(W, H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (onCluster) {
            lp.x = X;
            lp.y = Y;
        } else {
            lp.x = 40;
            lp.y = 40;
        }
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            state = "окно не открылось: " + Errors.text(e);
            DebugLog.w(TAG, "overlay: " + e);
            view = null;
            list = null;
            return false;
        }
        return true;
    }

    private void draw() {
        if (list == null) return;
        list.removeAllViews();
        int rowH = (H - TITLE_H) / VISIBLE_ROWS;
        if (rows.isEmpty()) {
            list.addView(row(c.getString(R.string.menu_empty), false, rowH));
            return;
        }
        for (int n = first; n < Math.min(rows.size(), first + VISIBLE_ROWS); n++) {
            list.addView(row(rows.get(n).row, n == selected, rowH));
        }
    }

    private View row(String text, boolean selected, int h) {
        TextView t = new TextView(list.getContext());
        t.setText(text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        t.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setTextColor(c.getColor(selected ? R.color.cluster_text : R.color.cluster_text_dim));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(18, 0, 18, 0);
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c.getColor(R.color.cluster_accent_fill));
            bg.setCornerRadius(8);
            t.setBackground(bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(W - 12, h);
        lp.leftMargin = 6;
        t.setLayoutParams(lp);
        return t;
    }
}
