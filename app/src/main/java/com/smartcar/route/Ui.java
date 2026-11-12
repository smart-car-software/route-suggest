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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The app's look, after the cluster's (MSpeed's): a deep navy night, panels with a thin cold
 * rim, one cyan accent that glows, big light type. Views made in code, as everywhere here.
 */
final class Ui {
    private final Context c;

    Ui(Context c) {
        this.c = c;
    }

    int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    int color(int id) {
        return c.getColor(id);
    }

    /** The screen's background: night blue, a little lighter at the top. */
    GradientDrawable screen() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color(R.color.bg_top), color(R.color.bg_bottom)});
        return g;
    }

    /** A panel: rounded, the rim catching a bit of the accent. */
    LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(R.color.card));
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), color(R.color.card_rim));
        l.setBackground(g);
        l.setPadding(dp(20), dp(16), dp(20), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        l.setLayoutParams(lp);
        return l;
    }

    /** A panel's heading: small caps, spaced, in the accent — like the cluster's labels. */
    TextView label(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.ROOT), 13, true);
        t.setTextColor(color(R.color.accent));
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(R.color.text_primary));
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        return t;
    }

    TextView hint(String s) {
        TextView t = text(s, 14, false);
        t.setTextColor(color(R.color.text_secondary));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** The big number of a panel (trips recorded, rows offered): the cluster's light digits. */
    TextView big(String s) {
        TextView t = text(s, 40, false);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setShadowLayer(dp(10), 0, 0, color(R.color.accent_glow));
        return t;
    }

    /** A setting: what it does, a line on it, and the toggle at the right. Tap anywhere toggles. */
    Switch toggle(LinearLayout into, String title, String sub, boolean on,
                  CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 18, false));
        if (sub != null && !sub.isEmpty()) {
            TextView h = hint(sub);
            h.setPadding(0, dp(2), dp(16), 0);
            texts.addView(h);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{color(R.color.accent), color(R.color.text_secondary)}));
        s.setTrackTintList(new ColorStateList(states, new int[]{color(R.color.accent_track), color(R.color.track_off)}));
        s.setScaleX(1.25f);
        s.setScaleY(1.25f);
        s.setOnCheckedChangeListener(l);
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        into.addView(row);
        return s;
    }

    /** A line of the state panel: a dot (green — fine, amber — to do, grey — off) and the text. */
    TextView status(LinearLayout into, int state, String s, View.OnClickListener fix) {
        TextView t = text(s, 16, false);
        t.setPadding(0, dp(5), 0, dp(5));
        t.setCompoundDrawablePadding(dp(12));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        int col = state == OK ? color(R.color.ok) : state == TODO ? color(R.color.warn) : color(R.color.text_secondary);
        dot.setColor(col);
        dot.setSize(dp(10), dp(10));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        if (fix != null) {
            t.setTextColor(color(R.color.text_primary));
            t.setText(s + "  ›");
            t.setOnClickListener(fix);
            t.setBackground(ripple(Color.TRANSPARENT, 8));
        }
        into.addView(t);
        return t;
    }

    static final int OK = 0;
    static final int TODO = 1;
    static final int OFF = 2;

    /** The one strong action of a screen: filled with the accent, glowing. */
    TextView primary(String s, View.OnClickListener l) {
        TextView b = text(s, 19, true);
        b.setTextColor(color(R.color.on_accent));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(24), dp(16), dp(24), dp(16));
        b.setBackground(ripple(color(R.color.accent), 14));
        b.setElevation(dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    /** A quiet action: accent text on a dim pill. */
    TextView action(String s, View.OnClickListener l) {
        TextView b = text(s, 16, true);
        b.setTextColor(color(R.color.accent));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setBackground(ripple(color(R.color.accent_dim), 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** A row of a list (an account, an offered place): tappable, the whole width. */
    TextView item(String title, String sub, View.OnClickListener l) {
        TextView t = text(title, 17, false);
        if (sub != null && !sub.isEmpty()) {
            t.setText(title + "\n" + sub);
        }
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(ripple(color(R.color.item), 12));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    /** A small pill (the version, «доступна v0.9.0»). */
    TextView pill(String s, int bg, int fg) {
        TextView t = text(s, 13, true);
        t.setTextColor(fg);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(20));
        t.setBackground(g);
        return t;
    }

    RippleDrawable ripple(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(color(R.color.accent_glow)), g, mask);
    }
}
