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
 * A calendar event worth a route ({@link EventFilter}): going on now or starting within the
 * hour, with coordinates or at least an address to search for.
 */
final class CalEvent {
    final String title;
    final long startMs;
    final long endMs;
    final String location;
    final String place;
    final double lat;
    final double lon;

    CalEvent(String title, long startMs, long endMs, String location, String place, double lat,
             double lon) {
        this.title = title;
        this.startMs = startMs;
        this.endMs = endMs;
        this.location = location == null ? "" : location;
        this.place = place == null ? "" : place;
        this.lat = lat;
        this.lon = lon;
    }

    boolean hasGeo() {
        return Geo.valid(lat, lon);
    }
}
