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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen: what works and what is missing, «Войти в Яндекс» (a QR to confirm on the
 * phone), «Предложить сейчас», and the offered rows as big buttons when the cluster card cannot
 * be shown. Everything else is automatic.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_SHOW = "show_suggestions";
    private static final long LOGIN_WAIT_MS = 5 * 60_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status;
    private LinearLayout suggestions;
    private LinearLayout yandexBox;
    private Button grantLocation;
    private LinearLayout calendarBox;
    private Button naviHistory;
    private volatile YandexAccount.Login login;

    static void showSuggestions(Context c) {
        Intent i = new Intent(c, MainActivity.class).putExtra(EXTRA_SHOW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            DebugLog.w("Main", "cannot open: " + e);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.background));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text(getString(R.string.app_name), 26, true));
        root.addView(hint(getString(R.string.intro)));

        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        root.addView(suggestions);

        status = text("", 18, false);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

        root.addView(button(getString(R.string.suggest_now), v -> {
            RouteService.start(this, true);
            status.postDelayed(this::refresh, 3000);
        }));
        yandexBox = new LinearLayout(this);
        yandexBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(yandexBox);
        TextView calTitle = text(getString(R.string.cal_section), 20, true);
        calTitle.setPadding(0, dp(16), 0, 0);
        root.addView(calTitle);
        calendarBox = new LinearLayout(this);
        calendarBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(calendarBox);
        root.addView(button(getString(R.string.cal_add), v ->
                startActivity(new Intent(this, CalendarActivity.class))));
        grantLocation = button(getString(R.string.grant_location), v -> requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1));
        root.addView(grantLocation, 2);
        naviHistory = button("", v -> toggleNaviHistory());
        root.addView(naviHistory);
        root.addView(hint(getString(R.string.navi_history_hint)));
        root.addView(button(getString(R.string.grant_navi), v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (RuntimeException e) {
                status.setText(R.string.grant_navi_failed);
            }
        }));
        root.addView(hint(getString(R.string.navi_hint)));
        root.addView(button(getString(R.string.clear_trips), v -> new AlertDialog.Builder(this)
                .setMessage(R.string.clear_trips_q)
                .setPositiveButton(R.string.clear, (d, w) -> worker.execute(() -> {
                    TripStore.get(this).clear();
                    main.post(this::refresh);
                }))
                .setNegativeButton(R.string.cancel, null).show()));

        if (!RouteService.isStarted()) RouteService.start(this, false);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        showOffered(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        showOffered(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        login = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            // The service asked for updates without the permission: start it over.
            stopService(new Intent(this, RouteService.class));
            RouteService.start(this, false);
        }
        refresh();
    }

    /** The offered rows as buttons: the card could not go on the cluster. */
    private void showOffered(Intent i) {
        suggestions.removeAllViews();
        if (i == null || !i.getBooleanExtra(EXTRA_SHOW, false)) return;
        List<Suggestion> list = RouteService.offered(this);
        if (list.isEmpty()) return;
        suggestions.addView(text(getString(R.string.card_title), 22, true));
        for (int n = 0; n < list.size(); n++) {
            final int index = n;
            suggestions.addView(button(list.get(n).row, v -> {
                RouteService.choose(this, index);
                suggestions.removeAllViews();
            }));
        }
    }

    private void refresh() {
        worker.execute(() -> {
            int trips = TripStore.get(this).count();
            String s = statusText(trips);
            List<CalAccount> accounts = CalStore.load(this);
            main.post(() -> {
                status.setText(s);
                drawCalendars(accounts);
                naviHistory.setText(NaviReader.enabled(this) ? R.string.navi_history_off
                        : R.string.navi_history_on);
                grantLocation.setVisibility(checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        ? View.GONE : View.VISIBLE);
                drawYandex();
            });
        });
    }

    private String statusText(int trips) {
        StringBuilder sb = new StringBuilder();
        boolean located = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        line(sb, NotifyClient.installed(this), getString(R.string.st_notify_ok),
                getString(R.string.st_notify_missing));
        line(sb, Navigator.installed(this, Navigator.NAVI), getString(R.string.st_navi_ok),
                getString(R.string.st_navi_missing));
        line(sb, located, getString(R.string.st_location_ok, trips),
                getString(R.string.st_location_missing));
        YandexAccount ya = RouteApp.yandex(this);
        if (ya.loggedIn()) {
            int marks = 0;
            boolean home = false, work = false;
            for (Place p : ya.places()) {
                if (p.kind == Place.Kind.HOME) home = true;
                else if (p.kind == Place.Kind.WORK) work = true;
                else if (p.kind == Place.Kind.BOOKMARK) marks++;
            }
            String when = ya.syncedMs() > 0 ? DateUtils.getRelativeTimeSpanString(ya.syncedMs())
                    .toString() : "—";
            line(sb, true, getString(R.string.st_yandex_ok, marks,
                    home ? "✓" : "—", work ? "✓" : "—", when), "");
        } else {
            line(sb, false, "", ya.error().isEmpty() ? getString(R.string.st_yandex_missing)
                    : ya.error());
        }
        if (!NaviReader.enabled(this)) {
            line(sb, false, "", getString(R.string.st_navi_history_off));
        } else {
            String err = NaviReader.prefs(this).getString("error", "");
            long at = NaviReader.prefs(this).getLong("read_at", 0);
            line(sb, err.isEmpty(), getString(R.string.st_navi_history_ok, NaviReader.kept(this).size(),
                    at > 0 ? DateUtils.getRelativeTimeSpanString(at).toString() : "—"),
                    getString(R.string.st_navi_history_err, err));
        }
        line(sb, NaviListener.granted(this), getString(R.string.st_listener_ok),
                getString(R.string.st_listener_missing));
        return sb.toString().trim();
    }

    /** On: read once now (the unit may ask «allow debugging?»), and from then on at each start. */
    private void toggleNaviHistory() {
        if (NaviReader.enabled(this)) {
            NaviReader.prefs(this).edit().putBoolean("enabled", false).apply();
            refresh();
            return;
        }
        naviHistory.setEnabled(false);
        status.setText(R.string.navi_history_connecting);
        worker.execute(() -> {
            String err = "";
            try {
                NaviReader.read(this);
                NaviReader.prefs(this).edit().putBoolean("enabled", true).apply();
            } catch (IOException e) {
                err = Errors.text(e);
            }
            final String error = err;
            main.post(() -> {
                naviHistory.setEnabled(true);
                refresh();
                if (!error.isEmpty()) {
                    status.postDelayed(() -> status.setText(getString(R.string.st_navi_history_err, error)), 500);
                }
            });
        });
    }

    /** Each connected account: its state, and a tap to remove it. */
    private void drawCalendars(List<CalAccount> accounts) {
        calendarBox.removeAllViews();
        if (accounts.isEmpty()) {
            calendarBox.addView(hint(getString(R.string.cal_none)));
            return;
        }
        for (CalAccount a : accounts) {
            String state = !a.error.isEmpty() ? "• " + a.error
                    : "✓ " + getResources().getQuantityString(R.plurals.cal_count, a.enabled(), a.enabled());
            Button b = button(a.title() + "\n" + state, v -> new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.cal_remove_q, a.title()))
                    .setPositiveButton(R.string.cal_remove, (d, w) -> {
                        CalStore.remove(this, a.id);
                        refresh();
                    })
                    .setNegativeButton(R.string.cancel, null).show());
            b.setTextSize(16);
            calendarBox.addView(b);
        }
    }

    private static void line(StringBuilder sb, boolean ok, String good, String bad) {
        sb.append(ok ? "✓ " + good : "• " + bad).append('\n');
    }

    // ------------------------------------------------------------------ Yandex

    private void drawYandex() {
        yandexBox.removeAllViews();
        YandexAccount ya = RouteApp.yandex(this);
        YandexAccount.Login l = login;
        if (l != null && !l.link.isEmpty()) {
            yandexBox.addView(text(getString(R.string.login_title), 20, true));
            yandexBox.addView(hint(getString(R.string.login_hint)));
            ImageView qr = new ImageView(this);
            Bitmap bmp = Qr.of(l.link, dp(280));
            if (bmp != null) qr.setImageBitmap(bmp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), dp(280));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(12);
            yandexBox.addView(qr, lp);
            yandexBox.addView(hint(l.link));
            yandexBox.addView(button(getString(R.string.cancel), v -> {
                login = null;
                drawYandex();
            }));
            return;
        }
        if (!ya.loggedIn()) {
            yandexBox.addView(button(getString(R.string.yandex_login), v -> startLogin()));
        } else {
            yandexBox.addView(button(getString(R.string.yandex_sync), v -> worker.execute(() -> {
                try {
                    ya.sync();
                } catch (IOException e) {
                    if (!(e instanceof YandexAccount.LoginExpired)) ya.setError(Errors.text(e));
                }
                main.post(this::refresh);
            })));
            yandexBox.addView(button(getString(R.string.yandex_logout), v -> {
                ya.logout();
                refresh();
            }));
        }
    }

    private void startLogin() {
        YandexAccount.Login l = new YandexAccount.Login();
        login = l;
        status.setText(R.string.login_starting);
        worker.execute(() -> {
            try {
                l.start();
            } catch (IOException e) {
                main.post(() -> {
                    login = null;
                    status.setText(getString(R.string.login_failed, Errors.text(e)));
                });
                return;
            }
            main.post(this::drawYandex);
            long until = System.currentTimeMillis() + LOGIN_WAIT_MS;
            while (login == l && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(3000);
                    if (l.check()) {
                        YandexAccount ya = RouteApp.yandex(this);
                        ya.saveLogin(l);
                        try {
                            ya.sync();
                        } catch (IOException e) {
                            ya.setError(Errors.text(e));
                        }
                        main.post(() -> {
                            login = null;
                            refresh();
                        });
                        return;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    // the network blinked: keep asking until the time is up
                }
            }
            main.post(() -> {
                if (login == l) {
                    login = null;
                    status.setText(R.string.login_timeout);
                    drawYandex();
                }
            });
        });
    }

    // ------------------------------------------------------------------ views

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(R.color.text_primary));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 15, false);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
