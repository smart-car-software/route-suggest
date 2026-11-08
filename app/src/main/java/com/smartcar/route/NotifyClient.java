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
import android.content.pm.PackageManager;

/**
 * Car Notification Service's request API (its docs/API.md), copied as the contract asks: the
 * card goes on the cluster, the steering wheel answers, the reply comes back to
 * {@link ReplyReceiver}.
 */
final class NotifyClient {
    static final String PACKAGE = "com.smartcar.notify";
    static final String RECEIVER = "com.smartcar.notify.RequestReceiver";
    static final String ACTION_SHOW = "com.smartcar.notify.action.SHOW";
    static final String ACTION_CANCEL = "com.smartcar.notify.action.CANCEL";

    static final String RESULT_ANSWERED = "answered";
    static final String RESULT_UNAVAILABLE = "unavailable";

    private NotifyClient() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** A list card: the volume roller scrolls, ⏯ picks. */
    static void list(Context c, String id, String title, String[] rows, long timeoutMs,
                     long expireMs, boolean sound, PendingIntent reply) {
        Intent i = base(ACTION_SHOW, id);
        i.putExtra("type", "list");
        i.putExtra("title", title);
        i.putExtra("options", rows);
        i.putExtra("timeout_ms", timeoutMs);
        if (expireMs > 0) i.putExtra("expire_ms", expireMs);
        i.putExtra("sound", sound);
        i.putExtra("reply", reply);
        c.sendBroadcast(i);
    }

    static void info(Context c, String id, String title, String text) {
        Intent i = base(ACTION_SHOW, id);
        i.putExtra("type", "info");
        i.putExtra("title", title);
        if (text != null) i.putExtra("text", text);
        c.sendBroadcast(i);
    }

    static void cancel(Context c, String id) {
        c.sendBroadcast(base(ACTION_CANCEL, id));
    }

    private static Intent base(String action, String id) {
        Intent i = new Intent(action);
        i.setClassName(PACKAGE, RECEIVER);
        i.putExtra("id", id);
        return i;
    }
}
