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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen, in the cluster's look ({@link Ui}): on the left what there is now — the trips,
 * «Предложить сейчас», the state of each part (a tap on one that needs something fixes it),
 * Yandex and the calendars; on the right the settings as toggles, the update and the journal.
 * What Android has to allow is asked in dialogs when the app opens, once each, not by buttons.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_SHOW = "show_suggestions";
    private static final long LOGIN_WAIT_MS = 5 * 60_000L;
    private static final long UPDATE_CHECK_MS = 6 * 3600_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private TextView trips;
    private TextView tripsSub;
    private LinearLayout suggestions;
    private LinearLayout states;
    private LinearLayout yandexBox;
    private LinearLayout calendarBox;
    private LinearLayout updateBox;
    private TextView updatePill;
    private TextView journal;
    private Switch card;
    private Switch menu;
    private Switch autoGo;
    private Switch history;
    /** Set while the toggles are being put right from the stored state: no listener then. */
    private boolean binding;
    private volatile YandexAccount.Login login;
    private volatile Updater.Release available;
    private boolean asked;

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
        ui = new Ui(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(ui.screen());
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(28), ui.dp(20), ui.dp(28), ui.dp(20));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(header());
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(columns);
        LinearLayout left = column(columns, ui.dp(10));
        LinearLayout right = column(columns, 0);

        // ---- left: now
        LinearLayout now = ui.card();
        now.addView(ui.label(getString(R.string.sec_now)));
        trips = ui.big("0");
        now.addView(trips);
        tripsSub = ui.hint("");
        tripsSub.setPadding(0, 0, 0, ui.dp(12));
        now.addView(tripsSub);
        now.addView(ui.primary(getString(R.string.suggest_now), v -> {
            RouteService.start(this, true);
            main.postDelayed(this::refresh, 3000);
        }));
        suggestions = new LinearLayout(this);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        now.addView(suggestions);
        left.addView(now);

        LinearLayout stateCard = ui.card();
        stateCard.addView(ui.label(getString(R.string.sec_state)));
        states = new LinearLayout(this);
        states.setOrientation(LinearLayout.VERTICAL);
        stateCard.addView(states);
        left.addView(stateCard);

        LinearLayout yandex = ui.card();
        yandex.addView(ui.label(getString(R.string.sec_yandex)));
        yandexBox = new LinearLayout(this);
        yandexBox.setOrientation(LinearLayout.VERTICAL);
        yandex.addView(yandexBox);
        left.addView(yandex);

        LinearLayout cal = ui.card();
        cal.addView(ui.label(getString(R.string.cal_section)));
        calendarBox = new LinearLayout(this);
        calendarBox.setOrientation(LinearLayout.VERTICAL);
        cal.addView(calendarBox);
        cal.addView(ui.action("＋  " + getString(R.string.cal_add), v ->
                startActivity(new Intent(this, CalendarActivity.class))));
        left.addView(cal);

        // ---- right: settings
        LinearLayout settings = ui.card();
        settings.addView(ui.label(getString(R.string.sec_settings)));
        card = ui.toggle(settings, getString(R.string.t_card), getString(R.string.card_hint),
                RouteService.cardAtStart(this), (v, on) -> {
                    if (binding) return;
                    RouteService.prefs(this).edit().putBoolean(RouteService.PREF_CARD, on).apply();
                });
        menu = ui.toggle(settings, getString(R.string.t_menu), getString(R.string.menu_hint),
                ClusterMenu.on(this), (v, on) -> {
                    if (binding) return;
                    RouteService.prefs(this).edit().putBoolean(ClusterMenu.PREF, on).apply();
                    RouteService.send(this, RouteService.ACTION_MENU_SWITCHED);
                    if (on && !ClusterMenu.granted(this)) grantMenu();
                    refresh();
                });
        autoGo = ui.toggle(settings, getString(R.string.t_autogo), getString(R.string.autogo_hint),
                AutoGoService.on(this), (v, on) -> {
                    if (binding) return;
                    RouteService.prefs(this).edit().putBoolean(AutoGoService.PREF, on).apply();
                    if (on && !AutoGoService.granted(this)) grantAutoGo();
                    refresh();
                });
        history = ui.toggle(settings, getString(R.string.t_history), getString(R.string.navi_history_hint),
                NaviReader.enabled(this), (v, on) -> {
                    if (binding) return;
                    if (on) enableNaviHistory(null);
                    else {
                        NaviReader.prefs(this).edit().putBoolean("enabled", false).apply();
                        refresh();
                    }
                });
        right.addView(settings);

        LinearLayout upd = ui.card();
        upd.addView(ui.label(getString(R.string.sec_update)));
        updateBox = new LinearLayout(this);
        updateBox.setOrientation(LinearLayout.VERTICAL);
        upd.addView(updateBox);
        right.addView(upd);

        LinearLayout log = ui.card();
        log.addView(ui.label(getString(R.string.journal)));
        log.addView(ui.hint(getString(R.string.journal_hint)));
        journal = ui.text("", 13, false);
        journal.setTypeface(Typeface.MONOSPACE);
        journal.setTextColor(getColor(R.color.text_secondary));
        journal.setTextIsSelectable(true);
        journal.setMaxLines(14);
        journal.setPadding(0, ui.dp(10), 0, 0);
        log.addView(journal);
        TextView forget = ui.action(getString(R.string.clear_trips), v -> new AlertDialog.Builder(this)
                .setMessage(R.string.clear_trips_q)
                .setPositiveButton(R.string.clear, (d, w) -> worker.execute(() -> {
                    TripStore.get(this).clear();
                    Learned.clear(this);
                    main.post(this::refresh);
                }))
                .setNegativeButton(R.string.cancel, null).show());
        forget.setTextColor(getColor(R.color.danger));
        log.addView(forget);
        right.addView(log);

        if (!RouteService.isStarted()) RouteService.start(this, false);
        showOffered(getIntent());
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, ui.dp(16));
        TextView title = ui.text(getString(R.string.app_name), 30, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setShadowLayer(ui.dp(12), 0, 0, getColor(R.color.accent_glow));
        h.addView(title);
        TextView v = ui.pill(BuildConfig.VERSION_NAME, getColor(R.color.item), getColor(R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ui.dp(16);
        h.addView(v, lp);
        View gap = new View(this);
        h.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        updatePill = ui.pill("", getColor(R.color.accent), getColor(R.color.on_accent));
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(x -> update());
        h.addView(updatePill);
        return h;
    }

    private LinearLayout column(LinearLayout into, int rightMargin) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = rightMargin;
        lp.leftMargin = rightMargin == 0 ? ui.dp(10) : 0;
        into.addView(c, lp);
        return c;
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
        checkUpdate(false);
        if (!asked) {
            asked = true;
            main.postDelayed(() -> askNext(0), 600);
        }
        // The Navigator's history: re-read when the last reading is old (or failed), so the
        // state is not a stale error.
        long at = NaviReader.prefs(this).getLong("read_at", 0);
        boolean failed = !NaviReader.prefs(this).getString("error", "").isEmpty();
        if (NaviReader.enabled(this) && (failed || System.currentTimeMillis() - at > 10 * 60_000L)) {
            worker.execute(() -> {
                try {
                    NaviReader.read(this);
                } catch (IOException e) {
                    NaviReader.prefs(this).edit().putString("error", Errors.text(e)).apply();
                }
                main.post(this::refresh);
            });
        }
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
        askNext(1);
    }

    // ------------------------------------------------------------------ first-run questions

    private SharedPreferences askedPrefs() {
        return getSharedPreferences("asked", MODE_PRIVATE);
    }

    /**
     * What Android has to allow, one dialog after another, each asked once («Позже» — it stays
     * in the state panel, a tap there asks again): the location, the overlay for the menu, the
     * accessibility service for «Поехали», the Navigator's history (root), its notifications.
     */
    private void askNext(int step) {
        if (isFinishing() || isDestroyed()) return;
        switch (step) {
            case 0:
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED && once("location")) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                    return; // onRequestPermissionsResult goes on
                }
                askNext(1);
                return;
            case 1:
                if (ClusterMenu.on(this) && !ClusterMenu.granted(this) && once("overlay")) {
                    dialog(R.string.q_overlay_title, R.string.q_overlay, () -> grantMenu(), 2);
                    return;
                }
                askNext(2);
                return;
            case 2:
                if (AutoGoService.on(this) && !AutoGoService.granted(this) && once("autogo")) {
                    dialog(R.string.q_autogo_title, R.string.q_autogo, () -> grantAutoGo(), 3);
                    return;
                }
                askNext(3);
                return;
            case 3:
                if (!NaviReader.enabled(this) && once("history")) {
                    dialog(R.string.q_history_title, R.string.q_history, () -> enableNaviHistory(null), 4);
                    return;
                }
                askNext(4);
                return;
            case 4:
                if (!NaviListener.granted(this) && once("listener")) {
                    dialog(R.string.q_listener_title, R.string.q_listener, this::openListenerSettings, 5);
                }
                return;
            default:
        }
    }

    /** True the first time only. */
    private boolean once(String what) {
        if (askedPrefs().getBoolean(what, false)) return false;
        askedPrefs().edit().putBoolean(what, true).apply();
        return true;
    }

    private void dialog(int title, int message, Runnable yes, int next) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.allow, (d, w) -> yes.run())
                .setNegativeButton(R.string.later, null)
                .setOnDismissListener(d -> main.postDelayed(() -> askNext(next), 400))
                .show();
    }

    // ------------------------------------------------------------------ the state

    /** The offered rows as big rows: the card could not go on the cluster. */
    private void showOffered(Intent i) {
        suggestions.removeAllViews();
        if (i == null || !i.getBooleanExtra(EXTRA_SHOW, false)) return;
        List<Suggestion> list = RouteService.offered(this);
        if (list.isEmpty()) return;
        TextView t = ui.text(getString(R.string.card_title), 20, true);
        t.setPadding(0, ui.dp(6), 0, 0);
        suggestions.addView(t);
        for (int n = 0; n < list.size(); n++) {
            final int index = n;
            suggestions.addView(ui.item(list.get(n).row, null, v -> {
                RouteService.choose(this, index);
                suggestions.removeAllViews();
            }));
        }
    }

    /** One line of the state panel. */
    private static final class State {
        final int kind;
        final String text;
        final Runnable fix;

        State(int kind, String text, Runnable fix) {
            this.kind = kind;
            this.text = text;
            this.fix = fix;
        }
    }

    private void refresh() {
        worker.execute(() -> {
            int count = TripStore.get(this).count();
            List<State> st = states();
            List<CalAccount> accounts = CalStore.load(this);
            String log = Journal.text(this);
            main.post(() -> {
                if (isDestroyed()) return;
                trips.setText(String.valueOf(count));
                tripsSub.setText(getResources().getQuantityString(R.plurals.trips_recorded, count));
                states.removeAllViews();
                for (State s : st) {
                    ui.status(states, s.kind, s.text, s.fix == null ? null : v -> s.fix.run());
                }
                journal.setText(log.isEmpty() ? "—" : log);
                drawCalendars(accounts);
                binding = true;
                card.setChecked(RouteService.cardAtStart(this));
                menu.setChecked(ClusterMenu.on(this));
                autoGo.setChecked(AutoGoService.on(this));
                history.setChecked(NaviReader.enabled(this));
                binding = false;
                drawYandex();
                drawUpdate();
            });
        });
    }

    private List<State> states() {
        List<State> out = new ArrayList<>();
        boolean located = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        out.add(NotifyClient.installed(this) ? new State(Ui.OK, getString(R.string.st_notify_ok), null)
                : new State(Ui.TODO, getString(R.string.st_notify_missing), null));
        out.add(Navigator.installed(this, Navigator.NAVI) ? new State(Ui.OK, getString(R.string.st_navi_ok), null)
                : new State(Ui.TODO, getString(R.string.st_navi_missing), null));
        out.add(located ? new State(Ui.OK, getString(R.string.st_location_on), null)
                : new State(Ui.TODO, getString(R.string.st_location_missing), () -> requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1)));
        if (!NaviReader.enabled(this)) {
            out.add(new State(Ui.OFF, getString(R.string.st_navi_history_off), () -> enableNaviHistory(null)));
        } else {
            String err = NaviReader.prefs(this).getString("error", "");
            long at = NaviReader.prefs(this).getLong("read_at", 0);
            out.add(err.isEmpty() ? new State(Ui.OK, getString(R.string.st_navi_history_ok,
                    NaviReader.kept(this).size(), at > 0 ? DateUtils.getRelativeTimeSpanString(at).toString() : "—"), null)
                    : new State(Ui.TODO, getString(R.string.st_navi_history_err, err), () -> enableNaviHistory(null)));
        }
        if (!ClusterMenu.on(this)) {
            out.add(new State(Ui.OFF, getString(R.string.st_menu_off), null));
        } else if (!ClusterMenu.granted(this)) {
            out.add(new State(Ui.TODO, getString(R.string.st_menu_missing), this::grantMenu));
        } else {
            boolean up = ClusterMenu.state.startsWith("подключено");
            out.add(new State(up ? Ui.OK : Ui.OFF, getString(R.string.st_menu_ok,
                    ClusterMenu.state.isEmpty() ? "—" : ClusterMenu.state), null));
        }
        if (!AutoGoService.on(this)) {
            out.add(new State(Ui.OFF, getString(R.string.st_autogo_off), null));
        } else {
            out.add(AutoGoService.granted(this) ? new State(Ui.OK, getString(R.string.st_autogo_ok), null)
                    : new State(Ui.TODO, getString(R.string.st_autogo_missing), this::grantAutoGo));
        }
        out.add(NaviListener.granted(this) ? new State(Ui.OK, getString(R.string.st_listener_ok), null)
                : new State(Ui.OFF, getString(R.string.st_listener_missing), this::openListenerSettings));
        return out;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private void openListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (RuntimeException e) {
            toast(getString(R.string.grant_navi_failed));
        }
    }

    /** The accessibility service: as root through the unit's adb, else Android's own screen. */
    private void grantAutoGo() {
        worker.execute(() -> {
            boolean ok;
            try {
                AutoGoService.enableAsRoot(this);
                RouteService.prefs(this).edit().putBoolean(AutoGoService.PREF_ROOT, true).apply();
                ok = true;
            } catch (IOException e) {
                DebugLog.w("AutoGo", "as root: " + Errors.text(e));
                ok = false;
            }
            boolean done = ok;
            main.post(() -> {
                if (!done) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException e) {
                        toast(getString(R.string.grant_navi_failed));
                    }
                }
                main.postDelayed(this::refresh, 1500);
            });
        });
    }

    /** «Поверх окон»: as root through the unit's adb, else Android's own screen. */
    private void grantMenu() {
        worker.execute(() -> {
            boolean ok;
            try {
                ClusterMenu.grantAsRoot(this);
                ok = true;
            } catch (IOException e) {
                DebugLog.w("Menu", "as root: " + Errors.text(e));
                ok = false;
            }
            boolean done = ok;
            main.post(() -> {
                if (!done) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        toast(getString(R.string.grant_navi_failed));
                    }
                }
                RouteService.send(this, RouteService.ACTION_MENU_SWITCHED);
                main.postDelayed(this::refresh, 1500);
            });
        });
    }

    /** Read once now (the unit may ask «allow debugging?»), and from then on at each start. */
    private void enableNaviHistory(Runnable after) {
        toast(getString(R.string.navi_history_connecting));
        worker.execute(() -> {
            String err = "";
            try {
                NaviReader.read(this);
                NaviReader.prefs(this).edit().putBoolean("enabled", true).apply();
            } catch (IOException e) {
                err = Errors.text(e);
            }
            String error = err;
            main.post(() -> {
                refresh();
                if (!error.isEmpty()) {
                    new AlertDialog.Builder(this).setTitle(R.string.t_history)
                            .setMessage(getString(R.string.st_navi_history_err, error))
                            .setPositiveButton(android.R.string.ok, null).show();
                }
                if (after != null) after.run();
            });
        });
    }

    /** Each connected account: its state, and a tap to open it. */
    private void drawCalendars(List<CalAccount> accounts) {
        calendarBox.removeAllViews();
        if (accounts.isEmpty()) {
            calendarBox.addView(ui.hint(getString(R.string.cal_none)));
            return;
        }
        for (CalAccount a : accounts) {
            String state = !a.error.isEmpty() ? "• " + a.error
                    : "✓ " + getResources().getQuantityString(R.plurals.cal_count, a.enabled(), a.enabled());
            // A tap opens the account (its calendars, «Отключить» inside): one stray touch in
            // the car must not remove anything.
            calendarBox.addView(ui.item(a.title(), state, v -> startActivity(
                    new Intent(this, CalendarActivity.class).putExtra(CalendarActivity.EXTRA_ACCOUNT, a.id))));
        }
    }

    // ------------------------------------------------------------------ update

    /** The latest release, looked at on opening (every few hours) — installed by the button only. */
    private void checkUpdate(boolean now) {
        SharedPreferences p = RouteService.prefs(this);
        if (!now && System.currentTimeMillis() - p.getLong("update_checked", 0) < UPDATE_CHECK_MS) {
            String tag = p.getString("update_tag", "");
            if (Updater.newer(tag, BuildConfig.VERSION_NAME)) {
                available = new Updater.Release(tag, p.getString("update_url", ""), p.getString("update_notes", ""));
            }
            drawUpdate();
            return;
        }
        worker.execute(() -> {
            String err = "";
            try {
                Updater.Release r = Updater.latest();
                p.edit().putLong("update_checked", System.currentTimeMillis())
                        .putString("update_tag", r == null ? "" : r.tag)
                        .putString("update_url", r == null ? "" : r.apkUrl)
                        .putString("update_notes", r == null ? "" : r.notes).apply();
                available = r != null && Updater.newer(r.tag, BuildConfig.VERSION_NAME) ? r : null;
            } catch (IOException e) {
                err = Errors.text(e);
            }
            String error = err;
            main.post(() -> {
                p.edit().putString("update_error", error).apply();
                drawUpdate();
                if (now && error.isEmpty() && available == null) toast(getString(R.string.upd_latest));
            });
        });
    }

    private void drawUpdate() {
        if (updateBox == null) return;
        updateBox.removeAllViews();
        Updater.Release r = available;
        if (r != null) {
            updatePill.setText(getString(R.string.upd_pill, r.tag));
            updatePill.setVisibility(View.VISIBLE);
            updateBox.addView(ui.text(getString(R.string.upd_available, r.tag), 18, false));
            if (!r.notes.trim().isEmpty()) {
                TextView n = ui.hint(r.notes.trim());
                n.setMaxLines(6);
                n.setPadding(0, ui.dp(6), 0, ui.dp(6));
                updateBox.addView(n);
            }
            updateBox.addView(ui.primary(getString(R.string.upd_install, r.tag), v -> update()));
        } else {
            updatePill.setVisibility(View.GONE);
            String err = RouteService.prefs(this).getString("update_error", "");
            updateBox.addView(ui.text(getString(R.string.upd_current, BuildConfig.VERSION_NAME), 18, false));
            if (!err.isEmpty()) updateBox.addView(ui.hint(getString(R.string.upd_error, err)));
            updateBox.addView(ui.action(getString(R.string.upd_check), v -> checkUpdate(true)));
        }
    }

    private void update() {
        Updater.Release r = available;
        if (r == null || r.apkUrl.isEmpty()) return;
        toast(getString(R.string.upd_downloading, r.tag));
        worker.execute(() -> {
            String err = "";
            try {
                File apk = Updater.download(this, r);
                Journal.add(this, "обновление: скачано " + r.tag);
                if (!Updater.installAsRoot(this, apk)) {
                    main.post(() -> {
                        if (!getPackageManager().canRequestPackageInstalls()) {
                            toast(getString(R.string.upd_allow_unknown));
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException ignored) {
                            }
                            return;
                        }
                        worker.execute(() -> {
                            try {
                                Updater.installWithPrompt(this, apk);
                            } catch (IOException | RuntimeException e) {
                                main.post(() -> toast(getString(R.string.upd_error, Errors.text(e))));
                            }
                        });
                    });
                }
            } catch (IOException e) {
                err = Errors.text(e);
            }
            String error = err;
            if (!error.isEmpty()) main.post(() -> toast(getString(R.string.upd_error, error)));
        });
    }

    // ------------------------------------------------------------------ Yandex

    private void drawYandex() {
        yandexBox.removeAllViews();
        YandexAccount ya = RouteApp.yandex(this);
        YandexAccount.Login l = login;
        if (l != null && !l.link.isEmpty()) {
            yandexBox.addView(ui.text(getString(R.string.login_title), 18, true));
            yandexBox.addView(ui.hint(getString(R.string.login_hint)));
            ImageView qr = new ImageView(this);
            Bitmap bmp = Qr.of(l.link, ui.dp(260));
            if (bmp != null) qr.setImageBitmap(bmp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(260), ui.dp(260));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = ui.dp(12);
            yandexBox.addView(qr, lp);
            yandexBox.addView(ui.action(getString(R.string.cancel), v -> {
                login = null;
                drawYandex();
            }));
            return;
        }
        if (!ya.loggedIn()) {
            yandexBox.addView(ui.hint(ya.error().isEmpty() ? getString(R.string.st_yandex_missing) : ya.error()));
            yandexBox.addView(ui.action(getString(R.string.yandex_login), v -> startLogin()));
            return;
        }
        int marks = 0;
        boolean home = false, work = false;
        for (Place p : ya.places()) {
            if (p.kind == Place.Kind.HOME) home = true;
            else if (p.kind == Place.Kind.WORK) work = true;
            else if (p.kind == Place.Kind.BOOKMARK) marks++;
        }
        String when = ya.syncedMs() > 0 ? DateUtils.getRelativeTimeSpanString(ya.syncedMs()).toString() : "—";
        yandexBox.addView(ui.text(getString(R.string.st_yandex_ok, marks, home ? "✓" : "—",
                work ? "✓" : "—", when), 16, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(ui.action(getString(R.string.yandex_sync), v -> worker.execute(() -> {
            try {
                ya.sync();
            } catch (IOException e) {
                if (!(e instanceof YandexAccount.LoginExpired)) ya.setError(Errors.text(e));
            }
            main.post(this::refresh);
        })));
        row.addView(ui.action(getString(R.string.yandex_logout), v -> new AlertDialog.Builder(this)
                .setMessage(R.string.yandex_logout_q)
                .setPositiveButton(R.string.yandex_logout, (d, w) -> {
                    ya.logout();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null).show()));
        yandexBox.addView(row);
    }

    private void startLogin() {
        YandexAccount.Login l = new YandexAccount.Login();
        login = l;
        toast(getString(R.string.login_starting));
        worker.execute(() -> {
            try {
                l.start();
            } catch (IOException e) {
                main.post(() -> {
                    login = null;
                    toast(getString(R.string.login_failed, Errors.text(e)));
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
                    toast(getString(R.string.login_timeout));
                    drawYandex();
                }
            });
        });
    }
}
