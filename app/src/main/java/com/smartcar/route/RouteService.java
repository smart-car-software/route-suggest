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
    static final String ACTION_MENU_TRIAL = "com.smartcar.route.MENU_TRIAL";
    static final String ACTION_MENU_SWITCHED = "com.smartcar.route.MENU_SWITCHED";
    static final String ACTION_LEARN = "com.smartcar.route.LEARN";
    static final String PREFS = "route";
    static final String CARD_ID = "com.smartcar.route:suggest";

    static final long WAIT_FIX_MS = 60_000L;
    static final long WAIT_NET_MS = 40_000L;
    static final long PROMPT_GAP_MS = 10 * 60_000L;
    static final long CARD_TIMEOUT_MS = 45_000L;
    static final long CARD_EXPIRE_MS = 3 * 60_000L;
    static final long YANDEX_SYNC_MS = 6 * 3600_000L;

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
    private volatile boolean destroyed;
    private ClusterMenu menu;

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
        restoreAutoGo();
        menu = new ClusterMenu(this, this::menuRows);
        if (ClusterMenu.on(this)) menu.start();
        // Created at boot (BootReceiver) — the ignition — or by opening the app.
        armPrompt(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_SUGGEST_NOW.equals(a)) armPrompt(true);
        else if (ACTION_MENU_TRIAL.equals(a)) menu.trial();
        else if (ACTION_LEARN.equals(a)) learnLater(intent.getStringExtra("query"), intent.getLongExtra("at", 0));
        else if (ACTION_MENU_SWITCHED.equals(a)) {
            if (ClusterMenu.on(this)) menu.start();
            else menu.hideNow();
        }
        return START_STICKY;
    }

    static void send(Context c, String action) {
        Intent i = new Intent(c, RouteService.class).setAction(action);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            DebugLog.w(TAG, "cannot start: " + e);
        }
    }

    @Override
    public void onDestroy() {
        started = false;
        destroyed = true;
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        if (lm != null) lm.removeUpdates(listener);
        if (menu != null) menu.stop();
        calendar.shutdownNow();
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
            name = Suggester.chosenName(s, at, t);
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
            readNavi(); // the route of this drive, before the Navigator syncs it away
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
            Journal.add(this, "запуск: карточка уже была меньше 10 минут назад");
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
            if (gone()) return;
            sleep(1_000);
        }
        if (gone()) return;
        readNavi();
        YandexAccount ya = RouteApp.yandex(this);
        if (ya.loggedIn() && System.currentTimeMillis() - ya.syncedMs() > YANDEX_SYNC_MS) {
            try {
                ya.sync();
            } catch (IOException e) {
                DebugLog.w(TAG, "Yandex: " + Errors.text(e));
                if (!(e instanceof YandexAccount.LoginExpired)) ya.setError(Errors.text(e));
            }
        }

        // The calendars, straight from their servers. Waited for only while the unit has no
        // network yet (Wi-Fi/LTE come up seconds after the engine), for WAIT_NET_MS at most: a
        // calendar server that is down must not hold the question up.
        List<CalEvent> events;
        long netFrom = System.currentTimeMillis();
        while (true) {
            CalendarSync.Result r = CalendarSync.events(this, System.currentTimeMillis(), ya.places());
            events = r.events;
            if (r.networkFailures == 0 || now || online()
                    || System.currentTimeMillis() - netFrom > WAIT_NET_MS || gone()) {
                break;
            }
            sleep(5_000);
        }
        if (gone()) return;
        events = withCoordinates(events);
        keepEvents(events);
        if (gone()) return;

        if (!now && NaviListener.guiding()) {
            Journal.add(this, "запуск: Навигатор уже ведёт по маршруту — без карточки");
            return;
        }
        if (!now) {
            Journal.add(this, "запуск: событий " + events.size() + ", геопозиция "
                    + (lastFixAt > armedFix ? "свежая" : lastFix != null ? "старая" : "нет"));
        }
        List<Suggestion> list = suggestions(events);
        prefs(this).edit().putLong("prompt_at", System.currentTimeMillis())
                .putInt("prompt_boot", bootCount()).apply();
        main.post(() -> {
            if (!destroyed) show(list, now);
        });
    }

    /**
     * This service was destroyed (stopped and started again — after the location permission):
     * its question is dropped, the new one asks. A sleep interrupted by the shutdown only sets
     * the flag, so the loops would spin through their waits and ask twice (emulator, v0.6.1).
     */
    private boolean gone() {
        return destroyed || Thread.currentThread().isInterrupted();
    }

    /**
     * Events with only an address get coordinates (OSM Nominatim, nearest the car, cached by the
     * address): the Navigator then builds the route at once, where a search would need the
     * touchscreen. Not found — the event stays a search.
     */
    private List<CalEvent> withCoordinates(List<CalEvent> events) {
        List<CalEvent> out = new ArrayList<>();
        SharedPreferences cache = getSharedPreferences("geocode", MODE_PRIVATE);
        Location here = lastFix;
        for (CalEvent e : events) {
            if (e.hasGeo() || e.location.isEmpty()) {
                out.add(e);
                continue;
            }
            String key = EventFilter.norm(e.location);
            String hit = cache.getString(key, null);
            double[] geo = null;
            if (hit != null && hit.contains(",")) {
                String[] p = hit.split(",");
                geo = new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
            } else {
                double hLat = here == null ? Double.NaN : here.getLatitude();
                double hLon = here == null ? Double.NaN : here.getLongitude();
                geo = Geocode.forward(e.location, hLat, hLon);
                String bare = geo == null ? Geocode.withoutName(e.location) : null;
                if (bare != null) {
                    sleep(1_000); // Nominatim's policy: a request a second at most
                    geo = Geocode.forward(bare, hLat, hLon);
                }
                // A miss is not kept: it may be the network, not the address.
                if (geo != null) cache.edit().putString(key, geo[0] + "," + geo[1]).apply();
            }
            out.add(geo == null ? e : e.geocodedAt(geo[0], geo[1]));
        }
        return out;
    }

    /** The Navigator's own routes, when the user turned that on; a failure is only shown. */
    void readNavi() {
        if (!NaviReader.enabled(this)) return;
        try {
            NaviReader.read(this);
        } catch (IOException e) {
            DebugLog.w(TAG, "Navigator history: " + Errors.text(e));
            NaviReader.prefs(this).edit().putString("error", Errors.text(e)).apply();
        }
    }

    @SuppressWarnings("deprecation") // NetworkInfo: the plain answer on Android 9
    private boolean online() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private int bootCount() {
        return android.provider.Settings.Global.getInt(getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
    }

    /**
     * A force-stop of this app (Settings' «Остановить», a memory cleaner) takes AutoGoService out
     * of the enabled services for good — Android does that (seen on the emulator). Once turned
     * on as root, it is turned on again the same way at each start.
     */
    private void restoreAutoGo() {
        if (AutoGoService.granted(this)) {
            // On already (maybe from an older version): remember it, to restore it later.
            prefs(this).edit().putBoolean(AutoGoService.PREF_ROOT, true).apply();
            return;
        }
        if (!AutoGoService.on(this)
                || !prefs(this).getBoolean(AutoGoService.PREF_ROOT, false)) {
            return;
        }
        worker.execute(() -> {
            try {
                AutoGoService.enableAsRoot(this);
                Journal.add(this, "«Поехали»: спецвозможность была выключена — включена снова");
            } catch (IOException e) {
                Journal.add(this, "«Поехали»: спецвозможность выключена, включить не удалось: "
                        + Errors.text(e));
            }
        });
    }

    // ------------------------------------------------------------------ the menu on the cluster

    /** The calendar as last read, for the menu: read again when older than this. */
    static final long EVENTS_FRESH_MS = 10 * 60_000L;
    private volatile List<CalEvent> lastEvents = Collections.emptyList();
    private volatile long eventsAt;
    private volatile boolean eventsReading;
    private final ExecutorService calendar = Executors.newSingleThreadExecutor();

    private void keepEvents(List<CalEvent> events) {
        lastEvents = events;
        eventsAt = System.currentTimeMillis();
        if (menu != null) menu.update();
    }

    /** The menu's rows: at once from what is known, the calendar re-read behind when old. */
    private List<Suggestion> menuRows() {
        if (System.currentTimeMillis() - eventsAt > EVENTS_FRESH_MS && !eventsReading) {
            eventsReading = true;
            calendar.execute(() -> {
                try {
                    CalendarSync.Result r = CalendarSync.events(this, System.currentTimeMillis(),
                            RouteApp.yandex(this).places());
                    keepEvents(withCoordinates(r.events));
                } catch (RuntimeException e) {
                    DebugLog.w(TAG, "calendar for the menu: " + e);
                } finally {
                    eventsReading = false;
                }
            });
        }
        return Suggester.menu(input(lastEvents));
    }

    /** What to offer now, from everything known. */
    List<Suggestion> suggestions(List<CalEvent> events) {
        return Suggester.suggest(input(events));
    }

    private Suggester.Input input(List<CalEvent> events) {
        Suggester.Input in = new Suggester.Input();
        in.nowMs = System.currentTimeMillis();
        in.zone = ZoneId.systemDefault();
        Location here = lastFix;
        if (here != null) {
            in.hereLat = here.getLatitude();
            in.hereLon = here.getLongitude();
        }
        YandexAccount ya = RouteApp.yandex(this);
        // Drives recorded here and the routes the Navigator on this unit built.
        List<Trip> navi = new ArrayList<>();
        for (NaviHistory.Route r : NaviReader.kept(this)) navi.add(r.trip());
        in.trips = NaviHistory.merge(TripStore.get(this).since(in.nowMs - Suggester.HISTORY_MS), navi);
        in.events = events;
        in.places = ya.places();
        in.learned = Learned.load(this);
        return in;
    }

    /** The switch «Карточка при запуске» in the app (on unless turned off). */
    static final String PREF_CARD = "card_at_start";

    static boolean cardAtStart(Context c) {
        return prefs(c).getBoolean(PREF_CARD, true);
    }

    private void show(List<Suggestion> list, boolean asked) {
        if (!asked && !cardAtStart(this)) {
            Journal.add(this, "запуск: карточка выключена в приложении");
            return;
        }
        if (!asked && !Suggester.worthAsking(list)) {
            // Home, work, the frequent places: in the menu on the cluster, no card for them.
            Journal.add(this, "запуск: нет события в календаре и привычной поездки — без карточки");
            return;
        }
        if (!asked) Journal.add(this, "запуск: карточка, строк " + list.size());
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
        return choose(c, offered.get(index));
    }

    /** A row from the card or from the menu on the cluster. */
    static boolean choose(Context c, Suggestion s) {
        try {
            prefs(c).edit().putString("chosen", new JSONObject().put("at", System.currentTimeMillis())
                    .put("s", s.toJson()).toString()).apply();
        } catch (JSONException ignored) {
        }
        // Arm before the Navigator comes up: its route overview is what AutoGoService waits for.
        // Alice leads by herself — nothing to press then.
        boolean alice = Navigator.viaAlice(s, Navigator.online(c));
        if (!alice && (s.hasGeo() || Navigator.latLon(s.query) != null)) {
            AutoGoService.arm(c);
        }
        boolean ok = Navigator.open(c, s);
        if (ok && alice && NaviReader.enabled(c)) {
            // Where Alice sent the Navigator: kept for this row, for a route without the network.
            Intent i = new Intent(c, RouteService.class).setAction(ACTION_LEARN)
                    .putExtra("query", s.query).putExtra("at", System.currentTimeMillis());
            try {
                c.startForegroundService(i);
            } catch (RuntimeException e) {
                DebugLog.w(TAG, "cannot start: " + e);
            }
        }
        return ok;
    }

    /** Reads at 1 and 3 minutes: the Navigator's deltas go once it syncs. */
    private static final long[] LEARN_AFTER_MS = {60_000L, 180_000L};

    private void learnLater(String query, long at) {
        for (long after : LEARN_AFTER_MS) {
            main.postDelayed(() -> {
                if (!destroyed) worker.execute(() -> learn(query, at));
            }, after);
        }
    }

    private void learn(String query, long at) {
        if (destroyed || query == null || query.isEmpty()) return;
        if (Learned.load(this).containsKey(Learned.key(query))
                && prefs(this).getLong("learned_at:" + Learned.key(query), 0) >= at) {
            return; // the first reading had it
        }
        try {
            double[] p = NaviReader.builtBetween(this, at, at + Learned.WINDOW_MS);
            if (p == null) {
                Journal.add(this, "Алиса: точки маршрута в истории Навигатора пока нет");
                return;
            }
            Learned.put(this, query, p[0], p[1]);
            prefs(this).edit().putLong("learned_at:" + Learned.key(query), at).apply();
            Journal.add(this, "Алиса: точка маршрута запомнена — без интернета маршрут пойдёт в неё");
        } catch (IOException e) {
            Journal.add(this, "Алиса: история Навигатора не прочиталась: " + Errors.text(e));
        }
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
