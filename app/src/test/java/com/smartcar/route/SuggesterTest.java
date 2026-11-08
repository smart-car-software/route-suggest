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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SuggesterTest {
    static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    static final double[] HOME = {55.800, 49.100};
    static final double[] WORK = {55.750, 49.200};
    static final double[] GYM = {55.780, 49.150};
    static final double[] SHOP = {55.820, 49.050};
    static final List<Place> YANDEX = Arrays.asList(
            new Place(Place.Kind.HOME, "Дом", "", HOME[0], HOME[1]),
            new Place(Place.Kind.WORK, "Работа", "", WORK[0], WORK[1]),
            new Place(Place.Kind.BOOKMARK, "Ice Power", "", GYM[0], GYM[1] + 0.001));

    static long at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(MSK).toInstant().toEpochMilli();
    }

    static Trip trip(long startMs, double[] from, double[] to) {
        return new Trip(startMs, from[0], from[1], startMs + 20 * 60_000L, to[0], to[1]);
    }

    static Suggester.Input input(long now, double[] here, List<Trip> trips, List<Place> places) {
        Suggester.Input in = new Suggester.Input();
        in.nowMs = now;
        in.zone = MSK;
        if (here != null) {
            in.hereLat = here[0];
            in.hereLon = here[1];
        }
        in.trips = trips;
        in.places = places;
        return in;
    }

    static List<String> rows(List<Suggestion> s) {
        List<String> out = new ArrayList<>();
        for (Suggestion x : s) out.add(x.row);
        return out;
    }

    // 2026-11-10 is a Tuesday.
    @Test
    public void everyTuesdayToTheGym() {
        List<Trip> trips = Arrays.asList(trip(at(10, 20, 18, 5), HOME, GYM),
                trip(at(10, 27, 17, 50), HOME, GYM), trip(at(11, 3, 18, 20), HOME, GYM));
        List<Suggestion> s = Suggester.suggest(input(at(11, 10, 17, 45), HOME, trips, YANDEX));
        assertEquals(Arrays.asList("Ice Power · по вторникам", "На работу"), rows(s));
        assertEquals(Suggestion.Kind.ROUTINE, s.get(0).kind);
        assertEquals("Ice Power", s.get(0).name);
        // Wednesday: not a routine (and three drives are «frequent»).
        List<Suggestion> wed = Suggester.suggest(input(at(11, 11, 17, 45), HOME, trips, YANDEX));
        assertEquals(Arrays.asList("На работу", "Ice Power"), rows(wed));
        // Tuesday morning: out of the window.
        List<Suggestion> morning = Suggester.suggest(input(at(11, 10, 9, 0), HOME, trips, YANDEX));
        assertEquals("На работу", morning.get(0).row);
    }

    @Test
    public void nothingToOfferThenHomeOrWork() {
        List<Trip> none = Collections.emptyList();
        assertEquals(Collections.singletonList("На работу"),
                rows(Suggester.suggest(input(at(11, 8, 9, 0), HOME, none, YANDEX))));
        assertEquals(Collections.singletonList("Домой"),
                rows(Suggester.suggest(input(at(11, 8, 9, 0), SHOP, none, YANDEX))));
        // At work: home.
        assertEquals(Collections.singletonList("Домой"),
                rows(Suggester.suggest(input(at(11, 8, 19, 0), WORK, none, YANDEX))));
        // No fix: home, as «not known to be at home».
        assertEquals(Collections.singletonList("Домой"),
                rows(Suggester.suggest(input(at(11, 8, 9, 0), null, none, YANDEX))));
        // Nothing known at all.
        assertTrue(Suggester.suggest(input(at(11, 8, 9, 0), HOME, none,
                Collections.emptyList())).isEmpty());
    }

    @Test
    public void calendarFirst() {
        Suggester.Input in = input(at(11, 10, 17, 45), HOME, Collections.emptyList(), YANDEX);
        in.events = Arrays.asList(
                new CalEvent("Врач", at(11, 10, 18, 30), at(11, 10, 19, 0), "Чистопольская 20", "",
                        Double.NaN, Double.NaN),
                new CalEvent("Ужин", at(11, 10, 18, 0), at(11, 10, 20, 0), "Ленина 5", "Ильяс",
                        SHOP[0], SHOP[1]),
                new CalEvent("Совещание", at(11, 10, 17, 0), at(11, 10, 18, 0), "", "",
                        WORK[0], WORK[1]),
                new CalEvent("Было", at(11, 10, 16, 0), at(11, 10, 17, 0), "", "", GYM[0], GYM[1]));
        List<Suggestion> s = Suggester.suggest(in);
        assertEquals(Arrays.asList("Совещание · идёт", "Ужин · 18:00", "Врач · 18:30"), rows(s));
        assertEquals("Ильяс", s.get(1).name);
        assertFalse(s.get(2).hasGeo());
        assertEquals("Чистопольская 20", s.get(2).query);
        // «На работу» is the same place as the meeting: one row.
    }

    @Test
    public void notWhereTheCarIs() {
        Suggester.Input in = input(at(11, 10, 17, 45), GYM, Collections.emptyList(), YANDEX);
        in.events = Collections.singletonList(new CalEvent("Тренировка", at(11, 10, 18, 0),
                at(11, 10, 19, 0), "", "", GYM[0], GYM[1]));
        assertEquals(Collections.singletonList("Домой"), rows(Suggester.suggest(in)));
    }

    @Test
    public void frequentPlacesNamedByAddressOrChoice() {
        List<Trip> trips = new ArrayList<>();
        // Shop: four drives at scattered times, named by the address only.
        trips.add(trip(at(9, 1, 10, 0), HOME, SHOP).withLabel(null, "Баумана 1", false));
        trips.add(trip(at(9, 20, 15, 0), HOME, SHOP).withLabel(null, "Баумана 1", false));
        trips.add(trip(at(10, 9, 12, 0), HOME, SHOP).withLabel(null, "Баумана 3", false));
        trips.add(trip(at(10, 30, 20, 0), HOME, SHOP).withLabel("Ашан", "Баумана 1", true));
        List<Suggestion> s = Suggester.suggest(input(at(11, 8, 9, 0), HOME, trips, YANDEX));
        assertEquals(Arrays.asList("На работу", "Ашан"), rows(s));
        trips.set(3, trip(at(10, 30, 20, 0), HOME, SHOP));
        s = Suggester.suggest(input(at(11, 8, 9, 0), HOME, trips, YANDEX));
        assertEquals("Баумана 1", s.get(1).row);
    }

    @Test
    public void dailyRoutineAndLearnedHomeWork() {
        List<Trip> trips = new ArrayList<>();
        for (int d = 2; d <= 6; d++) { // Mon–Fri 2–6 Nov: to work at 8:30, back at 19:00
            trips.add(trip(at(11, d, 8, 30), HOME, WORK));
            trips.add(trip(at(11, d, 19, 0), WORK, HOME));
        }
        // No Yandex: home and work are learned from the drives.
        List<Suggestion> s = Suggester.suggest(input(at(11, 9, 8, 20), HOME, trips,
                Collections.emptyList()));
        assertEquals(Suggestion.Kind.ROUTINE, s.get(0).kind);
        assertTrue(s.get(0).row.endsWith("обычно в это время"));
        assertEquals(1, s.size()); // «На работу» and «work, frequent» are the same place
        List<Suggestion> evening = Suggester.suggest(input(at(11, 9, 18, 45), WORK, trips,
                Collections.emptyList()));
        assertEquals(Suggestion.Kind.ROUTINE, evening.get(0).kind);
        Suggester.Cluster home = null;
        for (Suggester.Cluster c : Suggester.clusters(trips)) if (c.trips.size() == 5 && Geo.meters(c.lat, c.lon, HOME[0], HOME[1]) < 10) home = c;
        assertTrue(home != null);
        Place learned = Suggester.homeOrLearned(Collections.emptyList(), Suggester.clusters(trips), MSK);
        assertEquals(HOME[0], learned.lat, 1e-6);
    }

    @Test
    public void atMostSixRows() {
        List<Trip> trips = new ArrayList<>();
        for (int p = 0; p < 10; p++) {
            double[] to = {55.70 + p * 0.01, 49.30};
            for (int k = 0; k < 4; k++) trips.add(trip(at(10, 1 + p + k * 5, 10, 0), HOME, to));
        }
        Suggester.Input in = input(at(11, 8, 9, 0), HOME, trips, YANDEX);
        List<Suggestion> s = Suggester.suggest(in);
        assertEquals(1 + Suggester.FREQUENT_ROWS, s.size());
        assertTrue(s.size() <= Suggester.MAX_ROWS);
    }
}
