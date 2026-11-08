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
 * A named place from Yandex: «Дом», «Работа» or a bookmark. An organisation bookmark may have no
 * coordinates (Yandex keeps only its id): then {@link #hasGeo()} is false and the Navigator is
 * asked to search for its name.
 */
final class Place {
    enum Kind { HOME, WORK, BOOKMARK }

    final Kind kind;
    final String name;
    final String address;
    final double lat;
    final double lon;

    Place(Kind kind, String name, String address, double lat, double lon) {
        this.kind = kind;
        this.name = name;
        this.address = address == null ? "" : address;
        this.lat = lat;
        this.lon = lon;
    }

    boolean hasGeo() {
        return Geo.valid(lat, lon);
    }

    double metersTo(double lat, double lon) {
        return hasGeo() && Geo.valid(lat, lon) ? Geo.meters(this.lat, this.lon, lat, lon)
                : Double.MAX_VALUE;
    }
}
