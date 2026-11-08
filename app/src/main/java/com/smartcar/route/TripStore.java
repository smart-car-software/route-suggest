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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** The drives, in SQLite on the unit. Nothing leaves the car. */
final class TripStore extends SQLiteOpenHelper {
    private static final String DB = "trips.db";
    private static final int VERSION = 1;

    private static TripStore instance;

    static synchronized TripStore get(Context c) {
        if (instance == null) instance = new TripStore(c.getApplicationContext());
        return instance;
    }

    private TripStore(Context c) {
        super(c, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL,"
                + " start_lat REAL NOT NULL, start_lon REAL NOT NULL, end_ms INTEGER NOT NULL,"
                + " end_lat REAL NOT NULL, end_lon REAL NOT NULL, name TEXT, address TEXT,"
                + " navigated INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX trips_end ON trips(end_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    long add(Trip t) {
        ContentValues v = new ContentValues();
        v.put("start_ms", t.startMs);
        v.put("start_lat", t.startLat);
        v.put("start_lon", t.startLon);
        v.put("end_ms", t.endMs);
        v.put("end_lat", t.endLat);
        v.put("end_lon", t.endLon);
        v.put("name", t.name);
        v.put("address", t.address);
        v.put("navigated", t.navigated ? 1 : 0);
        return getWritableDatabase().insert("trips", null, v);
    }

    void setAddress(long id, String address) {
        ContentValues v = new ContentValues();
        v.put("address", address);
        getWritableDatabase().update("trips", v, "id = ?", new String[]{String.valueOf(id)});
    }

    /** Drives that ended after sinceMs, oldest first. */
    List<Trip> since(long sinceMs) {
        List<Trip> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("trips", null, "end_ms >= ?",
                new String[]{String.valueOf(sinceMs)}, null, null, "end_ms")) {
            while (c.moveToNext()) {
                out.add(new Trip(c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getLong(c.getColumnIndexOrThrow("start_ms")),
                        c.getDouble(c.getColumnIndexOrThrow("start_lat")),
                        c.getDouble(c.getColumnIndexOrThrow("start_lon")),
                        c.getLong(c.getColumnIndexOrThrow("end_ms")),
                        c.getDouble(c.getColumnIndexOrThrow("end_lat")),
                        c.getDouble(c.getColumnIndexOrThrow("end_lon")),
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getString(c.getColumnIndexOrThrow("address")),
                        c.getInt(c.getColumnIndexOrThrow("navigated")) != 0));
            }
        }
        return out;
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM trips", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    void clear() {
        getWritableDatabase().delete("trips", null, null);
    }
}
