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

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The calendars, read straight from their servers (no server of our own in between): adding an
 * account finds its calendars, and at the start of a drive the events of the next hour are read
 * from every connected one. Blocking: call from a worker thread.
 */
final class CalendarSync {
    /** Events that started this long ago may still be going on. */
    static final long LOOKBACK_MS = 12 * 3600_000L;

    private CalendarSync() {
    }

    /**
     * Logs in and finds the calendars: the account comes back with its calendars (all on) and,
     * for Exchange, the EWS address found. Throws with a message for the driver.
     */
    static CalAccount connect(CalAccount a) throws IOException {
        a.calendars.clear();
        if (a.kind == CalAccount.Kind.EXCHANGE) {
            a.server = new Ews(a.login, a.password).find(a.server);
            a.calendars.add(new CalAccount.Cal(a.server, "Календарь", true));
        } else {
            String base = a.kind == CalAccount.Kind.CALDAV ? normalize(a.server) : a.kind.base;
            if (base.isEmpty()) throw new IOException("нужен адрес сервера");
            CalDav dav = new CalDav(a.login, a.password);
            try {
                for (CalDav.Calendar c : dav.discover(base)) {
                    a.calendars.add(new CalAccount.Cal(c.url, c.name, true));
                }
            } finally {
                dav.close();
            }
            if (a.kind == CalAccount.Kind.CALDAV) a.server = base;
            if (a.calendars.isEmpty()) throw new IOException("календарей не нашлось");
        }
        a.error = "";
        a.okAt = System.currentTimeMillis();
        return a;
    }

    static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        return s.endsWith("/") ? s : s + "/";
    }

    static final class Result {
        final List<CalEvent> events;
        /** Accounts that could not be reached (not a wrong password): worth a retry. */
        final int networkFailures;

        Result(List<CalEvent> events, int networkFailures) {
            this.events = events;
            this.networkFailures = networkFailures;
        }
    }

    /** Events of the next hour from every account; an account that fails keeps its error. */
    static Result events(Context c, long nowMs, List<Place> places) {
        List<CalAccount> accounts = CalStore.load(c);
        List<EventFilter.Entry> entries = new ArrayList<>();
        long from = nowMs - LOOKBACK_MS, to = nowMs + EventFilter.WINDOW_MIN * 60_000L;
        int network = 0;
        for (CalAccount a : accounts) {
            if (a.enabled() == 0) continue;
            try {
                entries.addAll(read(a, from, to));
                a.error = "";
                a.okAt = System.currentTimeMillis();
            } catch (CalDav.AuthException e) {
                a.error = "Неверный логин или пароль — подключите заново";
            } catch (IOException e) {
                a.error = Errors.text(e);
                network++;
            } catch (RuntimeException e) {
                a.error = "не разобрал ответ сервера";
            }
            if (!a.error.isEmpty()) DebugLog.w("Calendars", a.kind + ": " + a.error);
        }
        CalStore.saveState(c, accounts);
        return new Result(EventFilter.filter(entries, nowMs, places), network);
    }

    static List<EventFilter.Entry> read(CalAccount a, long from, long to) throws IOException {
        List<EventFilter.Entry> out = new ArrayList<>();
        if (a.kind == CalAccount.Kind.EXCHANGE) {
            for (Ews.Item it : new Ews(a.login, a.password).find(a.server, from, to)) {
                EventFilter.Entry e = new EventFilter.Entry();
                e.title = it.subject;
                e.startMs = it.startMs;
                e.endMs = it.endMs;
                e.allDay = it.allDay;
                e.free = it.cancelled || "Free".equalsIgnoreCase(it.freeBusy);
                e.location = it.location;
                e.work = true;
                out.add(e);
            }
            return out;
        }
        CalDav dav = new CalDav(a.login, a.password);
        try {
            ZoneId zone = ZoneId.systemDefault();
            for (CalAccount.Cal cal : a.calendars) {
                if (!cal.on) continue;
                List<ICal.VEvent> evs = dav.events(cal.url, from, to, zone);
                for (ICal.Occurrence o : ICal.occurrences(evs, from, to)) {
                    EventFilter.Entry e = new EventFilter.Entry();
                    e.title = o.event.summary;
                    e.startMs = o.startMs;
                    e.endMs = o.endMs;
                    e.allDay = o.event.allDay;
                    e.free = "CANCELLED".equals(o.event.status) || "TRANSPARENT".equals(o.event.transp);
                    e.location = o.event.location;
                    e.lat = o.event.lat;
                    e.lon = o.event.lon;
                    out.add(e);
                }
            }
        } finally {
            dav.close();
        }
        return out;
    }
}
