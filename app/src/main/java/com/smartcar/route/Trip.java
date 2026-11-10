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

/**
 * One drive: where and when it left, where and when it stopped. {@code name} is what the driver
 * chose in a suggestion (or the Navigator's route) when the drive ended there; {@code address}
 * is the reverse-geocoded end, for naming a place nobody bookmarked.
 */
final class Trip {
    final long id;
    final long startMs;
    final double startLat;
    final double startLon;
    final long endMs;
    final double endLat;
    final double endLon;
    final String name;
    final String address;
    final boolean navigated;
    /**
     * An organisation's title from the Navigator («Kazan Kremlin»): below the name the driver
     * chose and a bookmark, above an address.
     */
    String naviName;
    /**
     * Where the Navigator was told to go, when the drive came with a route of its own: Yandex's
     * point for the place (an organisation's entrance), better for a route than where the car
     * stopped (the other side of the building, a car park). NaN when unknown.
     */
    double destLat = Double.NaN;
    double destLon = Double.NaN;

    Trip(long id, long startMs, double startLat, double startLon, long endMs, double endLat,
         double endLon, String name, String address, boolean navigated) {
        this.id = id;
        this.startMs = startMs;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endMs = endMs;
        this.endLat = endLat;
        this.endLon = endLon;
        this.name = name;
        this.address = address;
        this.navigated = navigated;
    }

    Trip(long startMs, double startLat, double startLon, long endMs, double endLat, double endLon) {
        this(0, startMs, startLat, startLon, endMs, endLat, endLon, null, null, false);
    }

    Trip withLabel(String name, String address, boolean navigated) {
        Trip t = new Trip(id, startMs, startLat, startLon, endMs, endLat, endLon, name, address,
                navigated);
        t.naviName = naviName;
        t.destLat = destLat;
        t.destLon = destLon;
        return t;
    }

    Trip withDest(double lat, double lon) {
        Trip t = withLabel(name, address, navigated);
        t.destLat = lat;
        t.destLon = lon;
        return t;
    }

    boolean hasDest() {
        return Geo.valid(destLat, destLon);
    }

    Trip withNaviName(String naviName) {
        Trip t = withLabel(name, address, true);
        t.naviName = naviName;
        return t;
    }

    double meters() {
        return Geo.meters(startLat, startLon, endLat, endLon);
    }
}
