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
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The calendar accounts in the app's preferences; each password encrypted (AES-GCM) with a key
 * that lives in the Android Keystore and never leaves it, so a copy of the app's files alone
 * does not give the passwords away.
 */
final class CalStore {
    private static final String PREFS = "calendars";
    private static final String KEY_ALIAS = "route-suggest-calendars";

    private CalStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized List<CalAccount> load(Context c) {
        List<CalAccount> out = new ArrayList<>();
        SharedPreferences p = prefs(c);
        try {
            JSONArray a = new JSONArray(p.getString("accounts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                CalAccount acc = CalAccount.fromJson(a.getJSONObject(i));
                acc.password = decrypt(p.getString("pw_" + acc.id, ""));
                out.add(acc);
            }
        } catch (JSONException e) {
            DebugLog.w("Calendars", "accounts unreadable");
        }
        return out;
    }

    /** Adds or replaces (same id), the password with it. */
    static synchronized void save(Context c, CalAccount acc) throws GeneralSecurityException {
        List<CalAccount> all = load(c);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(acc.id)) {
                all.set(i, acc);
                found = true;
            }
        }
        if (!found) all.add(acc);
        SharedPreferences.Editor e = prefs(c).edit();
        e.putString("pw_" + acc.id, encrypt(acc.password));
        write(e, all);
    }

    /** Only the state (error, last read), not the password. */
    static synchronized void saveState(Context c, List<CalAccount> accounts) {
        List<CalAccount> all = load(c);
        for (CalAccount a : all) {
            for (CalAccount s : accounts) {
                if (a.id.equals(s.id)) {
                    a.error = s.error;
                    a.okAt = s.okAt;
                }
            }
        }
        write(prefs(c).edit(), all);
    }

    static synchronized void remove(Context c, String id) {
        List<CalAccount> all = load(c);
        all.removeIf(a -> a.id.equals(id));
        SharedPreferences.Editor e = prefs(c).edit().remove("pw_" + id);
        write(e, all);
    }

    private static void write(SharedPreferences.Editor e, List<CalAccount> all) {
        JSONArray a = new JSONArray();
        try {
            for (CalAccount acc : all) a.put(acc.toJson());
        } catch (JSONException ex) {
            return;
        }
        e.putString("accounts", a.toString()).apply();
    }

    // ------------------------------------------------------------------ Android Keystore

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        try {
            ks.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            DebugLog.w("Calendars", "a password could not be decrypted");
            return "";
        }
    }
}
