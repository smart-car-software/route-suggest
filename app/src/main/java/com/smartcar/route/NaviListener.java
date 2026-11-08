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

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Whether Yandex Navigator is leading a route right now — from its own ongoing notification,
 * the one it keeps while guiding («Фоновая навигация» on). This is what the Monjaro community
 * apps (mHUD and the like) read too, as far as anything public says: it carries the next
 * manoeuvre, the remaining distance and the arrival time, but not the destination.
 *
 * <p>Used for two things: no «Куда едем?» when the Navigator already leads a route (it resumed
 * one after the unit woke up), and a drive is marked as driven with the Navigator. Optional: the
 * user grants the notification access in the app; without it both are simply unknown.
 */
public final class NaviListener extends NotificationListenerService {
    private static volatile boolean guiding;
    private static volatile long changedMs;
    private static volatile boolean connected;

    static boolean guiding() {
        return connected && guiding;
    }

    static boolean connected() {
        return connected;
    }

    static long changedMs() {
        return changedMs;
    }

    static boolean granted(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, NaviListener.class).flattenToString());
    }

    @Override
    public void onListenerConnected() {
        connected = true;
        rescan();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isNavi(sbn)) rescan();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isNavi(sbn)) rescan();
    }

    private static boolean isNavi(StatusBarNotification sbn) {
        return sbn != null && Navigator.NAVI.equals(sbn.getPackageName());
    }

    /**
     * The Navigator's guidance notification. Seen on the emulator with the Monjaro mod 30.3.0:
     * while it leads a route it keeps one ongoing notification, «Navigator is running»,
     * category {@code navigation}, channel {@code foreground_notification}, no custom view and no
     * manoeuvres in it; without a route (map, route overview, after «finish») there is none.
     * So its very presence is the sign.
     */
    static boolean isGuidance(Notification n) {
        if (n == null || (n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        return Notification.CATEGORY_NAVIGATION.equals(n.category)
                || "foreground_notification".equals(n.getChannelId())
                || n.contentView != null;
    }

    private void rescan() {
        boolean now = false;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (isNavi(sbn) && isGuidance(sbn.getNotification())) now = true;
                }
            }
        } catch (SecurityException | NullPointerException e) {
            return;
        }
        if (now != guiding) {
            guiding = now;
            changedMs = System.currentTimeMillis();
            DebugLog.i("Navi", now ? "route guidance on" : "route guidance off");
            if (now) RouteService.markNavigated(this);
        }
    }
}
