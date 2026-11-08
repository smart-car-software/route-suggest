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

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Добавить календарь»: pick the service, type the login and the (app) password — with where to
 * get one, as a QR to open on the phone — and tick the calendars found. Three screens, no
 * server addresses for the popular services.
 */
public final class CalendarActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;

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
        pickService();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void pickService() {
        root.removeAllViews();
        root.addView(text(getString(R.string.cal_add_title), 24, true));
        root.addView(hint(getString(R.string.cal_add_hint)));
        for (CalAccount.Kind k : CalAccount.Kind.values()) {
            root.addView(button(k.title, v -> form(k)));
        }
        root.addView(hint(getString(R.string.cal_google)));
        root.addView(button(getString(R.string.cancel), v -> finish()));
    }

    private void form(CalAccount.Kind kind) {
        root.removeAllViews();
        root.addView(text(kind.title, 24, true));
        root.addView(hint(kind.help));
        if (!kind.helpUrl.isEmpty()) {
            Bitmap qr = Qr.of(kind.helpUrl, dp(200));
            if (qr != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(qr);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(200));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.topMargin = dp(8);
                root.addView(iv, lp);
            }
        }
        // CalDAV: the server first (it is needed); Exchange: last (it is found by itself).
        EditText server = null;
        if (kind == CalAccount.Kind.CALDAV) {
            server = field(getString(R.string.cal_server), InputType.TYPE_TEXT_VARIATION_URI);
            root.addView(server);
        }
        EditText login = field(kind.loginHint, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = field(getString(kind == CalAccount.Kind.EXCHANGE || kind == CalAccount.Kind.CALDAV
                ? R.string.cal_password : R.string.cal_app_password), InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(login);
        root.addView(password);
        if (kind == CalAccount.Kind.EXCHANGE) {
            server = field(getString(R.string.cal_server_optional), InputType.TYPE_TEXT_VARIATION_URI);
            root.addView(server);
        }
        TextView status = hint("");
        final EditText serverField = server;
        Button connect = button(getString(R.string.cal_connect), null);
        connect.setOnClickListener(v -> {
            CalAccount a = new CalAccount();
            a.kind = kind;
            a.login = login.getText().toString().trim();
            String pw = password.getText().toString();
            // An app password is shown in groups («abcd efgh …»); a real one may have spaces.
            a.password = kind == CalAccount.Kind.EXCHANGE || kind == CalAccount.Kind.CALDAV ? pw
                    : pw.replace(" ", "").trim();
            a.server = serverField == null ? "" : serverField.getText().toString().trim();
            if (a.login.isEmpty() || a.password.isEmpty()
                    || (kind == CalAccount.Kind.CALDAV && a.server.isEmpty())) {
                status.setText(R.string.cal_fill_in);
                return;
            }
            connect.setEnabled(false);
            status.setText(R.string.cal_connecting);
            worker.execute(() -> {
                try {
                    CalendarSync.connect(a);
                    main.post(() -> choose(a));
                } catch (CalDav.AuthException e) {
                    main.post(() -> {
                        connect.setEnabled(true);
                        status.setText(kind == CalAccount.Kind.EXCHANGE || kind == CalAccount.Kind.CALDAV
                                ? R.string.cal_wrong_password : R.string.cal_wrong_app_password);
                    });
                } catch (IOException | RuntimeException e) {
                    main.post(() -> {
                        connect.setEnabled(true);
                        status.setText(getString(R.string.cal_failed, e.getMessage()));
                    });
                }
            });
        });
        root.addView(connect);
        root.addView(status);
        root.addView(button(getString(R.string.back), v -> pickService()));
    }

    private void choose(CalAccount a) {
        root.removeAllViews();
        root.addView(text(a.kind.title, 24, true));
        root.addView(hint(getString(R.string.cal_choose)));
        for (CalAccount.Cal c : a.calendars) {
            CheckBox cb = new CheckBox(this);
            cb.setText(c.name);
            cb.setTextSize(18);
            cb.setTextColor(getColor(R.color.text_primary));
            cb.setChecked(c.on);
            cb.setOnCheckedChangeListener((v, on) -> c.on = on);
            root.addView(cb);
        }
        TextView status = hint("");
        root.addView(button(getString(R.string.cal_save), v -> {
            if (a.enabled() == 0) {
                status.setText(R.string.cal_none_chosen);
                return;
            }
            try {
                CalStore.save(this, a);
                finish();
            } catch (GeneralSecurityException e) {
                status.setText(getString(R.string.cal_failed, "не удалось зашифровать пароль"));
            }
        }));
        root.addView(status);
        root.addView(button(getString(R.string.cancel), v -> finish()));
    }

    // ------------------------------------------------------------------ views

    private EditText field(String hint, int variation) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(18);
        e.setTextColor(getColor(R.color.text_primary));
        e.setHintTextColor(getColor(R.color.text_secondary));
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(R.color.text_primary));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 15, false);
        t.setTextColor(getColor(R.color.text_secondary));
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        if (l != null) b.setOnClickListener(l);
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
