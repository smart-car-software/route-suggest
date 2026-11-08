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
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs while the unit is on: records the drives ({@link TripDetector} on GPS fixes) and, when
 * the car starts — the unit boots, or wakes after standing off — puts «Куда едем?» on the
 * cluster once.
 *
 * <p>The question waits for what it needs, a little: a fresh fix (the GPS comes up after the
 * engine) and the network for the calendar (Wi-Fi/LTE come up seconds later). After
 * {@link #WAIT_FIX_MS} / {@link #WAIT_NET_MS} it goes with what there is. Not again within
 * {@link #PROMPT_GAP_MS}, and not at all while the Navigator already leads a route.
 */
public final class RouteService extends Service {
    static final String ACTION_SUGGEST_NOW = "com.smartcar.route.SUGGEST_NOW";
    static final String PREFS = "route";
    static final String CARD_ID = "com.smartcar.route:suggest";

    static final long WAIT_FIX_MS = 60_000L;
    static final long WAIT_NET_MS = 40_000L;
    static final long PROMPT_GAP_MS = 10 * 60_000L;
    static final long CARD_TIMEOUT_MS = 45_000L;
    static final long CARD_EXPIRE_MS = 3 * 60_000L;
    static final long YANDEX_SYNC_MS = 6 * 3600_000L;
    /** A drive ending this close to the chosen row was a drive there. */
    static final double CHOSEN_M = 400;

    private static final String CHANNEL = "route";
    private static final String TAG = "Service";

    private static volatile boolean started;

    /** Database and geocoding. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The question: it may wait a minute or two for the GPS and the network. */
    private final ExecutorService prompter = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private TripDetector detector;
    private volatile Location lastFix;
    private volatile long lastFixAt;
    private volatile boolean promptRunning;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            onFix(l);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())
                    && detector.staleAt(System.currentTimeMillis())) {
                DebugLog.i(TAG, "screen on after a stop: a new drive");
                armPrompt(false);
            }
        }
    };

    static boolean isStarted() {
        return started;
    }

    static void start(Context c, boolean suggestNow) {
        Intent i = new Intent(c, RouteService.class);
        if (suggestNow) i.setAction(ACTION_SUGGEST_NOW);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "cannot start: " + e);
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** The Navigator started leading a route: the drive under way is a navigated one. */
    static void markNavigated(Context c) {
        prefs(c).edit().putLong("navigated_at", System.currentTimeMillis()).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        started = true;
        startForeground(1, notification());
        detector = new TripDetector(this::onTrip);
        detector.restore(prefs(this).getString("detector", ""));
        long now = System.currentTimeMillis();
        if (detector.staleAt(now)) {
            detector.endSession(); // the unit was off: the last drive ended at the last fix
            saveDetector();
        }
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        startLocation();
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_ON));
        // Created at boot (BootReceiver) — the ignition — or by opening the app.
        armPrompt(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SUGGEST_NOW.equals(intent.getAction())) armPrompt(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        started = false;
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        if (lm != null) lm.removeUpdates(listener);
        saveDetector();
        worker.shutdown();
        prompter.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ location and drives

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            DebugLog.w(TAG, "no location permission: drives are not recorded");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, listener,
                    Looper.getMainLooper());
        } catch (IllegalArgumentException | SecurityException e) {
            DebugLog.w(TAG, "GPS: " + e);
        }
        try {
            if (lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                Location l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (l != null && lastFix == null) lastFix = l;
            }
            Location g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (g != null) lastFix = g;
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void onFix(Location l) {
        if (l == null || (l.hasAccuracy() && l.getAccuracy() > 100)) return;
        long now = System.currentTimeMillis();
        // GPS time on a head unit can be anything until it has synced: the wall clock is safer.
        long t = Math.abs(l.getTime() - now) < 60_000L ? l.getTime() : now;
        if (detector.staleAt(t)) {
            DebugLog.i(TAG, "fixes after a long gap: a new drive");
            armPrompt(false);
        }
        detector.onFix(l.getLatitude(), l.getLongitude(), t);
        lastFix = l;
        lastFixAt = now;
        saveDetector();
    }

    private void saveDetector() {
        prefs(this).edit().putString("detector", detector.save()).apply();
    }

    /** A drive ended: name it after the row chosen for it, keep it, look up its address. */
    private void onTrip(Trip t) {
        SharedPreferences p = prefs(this);
        String name = null;
        try {
            JSONObject chosen = new JSONObject(p.getString("chosen", "{}"));
            long at = chosen.optLong("at");
            Suggestion s = chosen.has("s") ? Suggestion.fromJson(chosen.getJSONObject("s")) : null;
            if (s != null && s.hasGeo() && at >= t.startMs - 30 * 60_000L && at <= t.endMs
                    && Geo.meters(s.lat, s.lon, t.endLat, t.endLon) <= CHOSEN_M) {
                name = s.name;
            }
        } catch (JSONException ignored) {
        }
        boolean navigated = p.getLong("navigated_at", 0) >= t.startMs - 5 * 60_000L;
        Trip labelled = t.withLabel(name, null, navigated);
        DebugLog.i(TAG, "drive recorded: " + Math.round(t.meters()) + " m, "
                + Math.round((t.endMs - t.startMs) / 60_000.0) + " min" + (navigated ? ", navigated" : ""));
        worker.execute(() -> {
            long id = TripStore.get(this).add(labelled);
            String address = Geocode.reverse(t.endLat, t.endLon);
            if (!address.isEmpty()) TripStore.get(this).setAddress(id, address);
        });
    }

    // ------------------------------------------------------------------ «Куда едем?»

    /**
     * The pause between two questions holds within one boot only: a boot is the ignition, so
     * after one the question always comes (and the service restarted by the system mid-drive
     * does not ask again).
     */
    private void armPrompt(boolean now) {
        SharedPreferences p = prefs(this);
        boolean sameBoot = p.getInt("prompt_boot", -1) == bootCount();
        if (!now && sameBoot && System.currentTimeMillis() - p.getLong("prompt_at", 0) < PROMPT_GAP_MS) {
            return;
        }
        if (promptRunning) return;
        promptRunning = true;
        final long armedAt = System.currentTimeMillis();
        final long armedFix = lastFixAt;
        prompter.execute(() -> {
            try {
                runPrompt(now, armedAt, armedFix);
            } catch (RuntimeException e) {
                DebugLog.w(TAG, "question: " + e);
            } finally {
                promptRunning = false;
            }
        });
    }

    private void runPrompt(boolean now, long armedAt, long armedFix) {
        // A fix newer than the arming: after a sleep the last one is from where the car was.
        while (!now && lastFixAt <= armedFix && System.currentTimeMillis() - armedAt < WAIT_FIX_MS) {
            sleep(1_000);
        }
        YandexAccount ya = RouteApp.yandex(this);
        if (ya.loggedIn() && System.currentTimeMillis() - ya.syncedMs() > YANDEX_SYNC_MS) {
            try {
                ya.sync();
            } catch (IOException e) {
                DebugLog.w(TAG, "Yandex: " + e.getMessage());
                if (!(e instanceof YandexAccount.LoginExpired)) ya.setError(e.getMessage());
            }
        }

        // The calendars, straight from their servers; the network may still be coming up.
        List<CalEvent> events = Collections.emptyList();
        while (true) {
            CalendarSync.Result r = CalendarSync.events(this, System.currentTimeMillis(), ya.places());
            events = r.events;
            if (r.networkFailures == 0 || now
                    || System.currentTimeMillis() - armedAt > WAIT_FIX_MS + WAIT_NET_MS) {
                break;
            }
            sleep(5_000);
        }

        if (!now && NaviListener.guiding()) {
            DebugLog.i(TAG, "the Navigator already leads a route: no question");
            return;
        }
        List<Suggestion> list = suggestions(events);
        prefs(this).edit().putLong("prompt_at", System.currentTimeMillis())
                .putInt("prompt_boot", bootCount()).apply();
        main.post(() -> show(list, now));
    }

    private int bootCount() {
        return android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    /** What to offer now, from everything known. */
    List<Suggestion> suggestions(List<CalEvent> events) {
        Suggester.Input in = new Suggester.Input();
        in.nowMs = System.currentTimeMillis();
        in.zone = ZoneId.systemDefault();
        Location here = lastFix;
        if (here != null) {
            in.hereLat = here.getLatitude();
            in.hereLon = here.getLongitude();
        }
        in.trips = TripStore.get(this).since(in.nowMs - Suggester.HISTORY_MS);
        in.events = events;
        in.places = RouteApp.yandex(this).places();
        return Suggester.suggest(in);
    }

    private void show(List<Suggestion> list, boolean asked) {
        if (list.isEmpty()) {
            DebugLog.i(TAG, "nothing to offer");
            if (asked && NotifyClient.installed(this)) {
                NotifyClient.info(this, CARD_ID + ":none", getString(R.string.nothing_title),
                        getString(R.string.nothing_text));
            }
            return;
        }
        JSONArray offered = new JSONArray();
        String[] rows = new String[list.size() + 1];
        try {
            for (int i = 0; i < list.size(); i++) {
                rows[i] = list.get(i).row;
                offered.put(list.get(i).toJson());
            }
        } catch (JSONException e) {
            return;
        }
        rows[list.size()] = getString(R.string.not_now);
        prefs(this).edit().putString("offered", offered.toString()).apply();
        DebugLog.i(TAG, "offering " + list.size() + " rows");
        if (!NotifyClient.installed(this)) {
            MainActivity.showSuggestions(this);
            return;
        }
        Intent reply = new Intent(this, ReplyReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, reply,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotifyClient.list(this, CARD_ID, getString(R.string.card_title), rows, CARD_TIMEOUT_MS,
                CARD_EXPIRE_MS, true, pi);
    }

    /** The row the driver picked: remembered for naming the drive, and the route started. */
    static boolean choose(Context c, int index) {
        List<Suggestion> offered = offered(c);
        if (index < 0 || index >= offered.size()) return false; // «Не сейчас»
        Suggestion s = offered.get(index);
        try {
            prefs(c).edit().putString("chosen", new JSONObject().put("at", System.currentTimeMillis())
                    .put("s", s.toJson()).toString()).apply();
        } catch (JSONException ignored) {
        }
        return Navigator.open(c, s);
    }

    static List<Suggestion> offered(Context c) {
        List<Suggestion> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("offered", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Suggestion.fromJson(a.getJSONObject(i)));
        } catch (JSONException | IllegalArgumentException ignored) {
        }
        return out;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
