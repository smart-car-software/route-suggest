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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A connected calendar account: the service, the login, which of its calendars are read. The
 * password is not here: {@link CalStore} keeps it encrypted with a key of the Android Keystore.
 */
final class CalAccount {
    enum Kind {
        ICLOUD("iCloud", "https://caldav.icloud.com/", "Apple ID (почта)",
                "Нужен пароль приложения, не пароль Apple ID: account.apple.com → «Вход и безопасность» → «Пароли приложений» → «+». Наведите камеру телефона на код, чтобы открыть страницу.",
                "https://account.apple.com/account/manage/section/security"),
        YANDEX("Яндекс Календарь", "https://caldav.yandex.ru/", "Логин или почта Яндекса",
                "Нужен пароль приложения: id.yandex.ru → «Безопасность» → «Пароли приложений» → «Календарь CalDAV». Наведите камеру телефона на код, чтобы открыть страницу.",
                "https://id.yandex.ru/security/app-passwords"),
        MAILRU("Календарь Mail", "https://calendar.mail.ru/", "Почта Mail.ru",
                "Нужен пароль для внешнего приложения: id.mail.ru → «Безопасность» → «Пароли для внешних приложений». Наведите камеру телефона на код, чтобы открыть страницу.",
                "https://id.mail.ru/security"),
        EXCHANGE("Exchange (рабочий)", "", "Рабочая почта (user@company.ru)",
                "Обычный пароль от рабочей почты. Сервер найдётся сам; если нет — укажите адрес веб-почты (например, mail.company.ru). Microsoft 365 и Outlook.com по паролю больше не пускают.",
                ""),
        CALDAV("Другой CalDAV", "", "Логин",
                "Nextcloud, Synology, Fastmail, Radicale и другие: адрес сервера CalDAV, логин и пароль (лучше — пароль приложения).",
                "");

        final String title;
        final String base;
        final String loginHint;
        final String help;
        final String helpUrl;

        Kind(String title, String base, String loginHint, String help, String helpUrl) {
            this.title = title;
            this.base = base;
            this.loginHint = loginHint;
            this.help = help;
            this.helpUrl = helpUrl;
        }

        /** Whether the user types the server's address (otherwise it is known or found). */
        boolean asksServer() {
            return this == CALDAV || this == EXCHANGE;
        }
    }

    static final class Cal {
        final String url;
        final String name;
        boolean on;

        Cal(String url, String name, boolean on) {
            this.url = url;
            this.name = name;
            this.on = on;
        }
    }

    String id = UUID.randomUUID().toString();
    Kind kind = Kind.CALDAV;
    String login = "";
    /** CALDAV: the address typed; EXCHANGE: the EWS URL found (or typed). */
    String server = "";
    final List<Cal> calendars = new ArrayList<>();
    String error = "";
    long okAt;
    /** In memory only. */
    transient String password = "";

    String title() {
        return kind.title + " · " + login;
    }

    int enabled() {
        int n = 0;
        for (Cal c : calendars) if (c.on) n++;
        return n;
    }

    JSONObject toJson() throws JSONException {
        JSONArray cals = new JSONArray();
        for (Cal c : calendars) cals.put(new JSONObject().put("url", c.url).put("name", c.name).put("on", c.on));
        return new JSONObject().put("id", id).put("kind", kind.name()).put("login", login)
                .put("server", server).put("calendars", cals).put("error", error).put("okAt", okAt);
    }

    static CalAccount fromJson(JSONObject o) {
        CalAccount a = new CalAccount();
        a.id = o.optString("id", a.id);
        try {
            a.kind = Kind.valueOf(o.optString("kind"));
        } catch (IllegalArgumentException e) {
            a.kind = Kind.CALDAV;
        }
        a.login = o.optString("login");
        a.server = o.optString("server");
        a.error = o.optString("error");
        a.okAt = o.optLong("okAt");
        JSONArray cals = o.optJSONArray("calendars");
        if (cals != null) {
            for (int i = 0; i < cals.length(); i++) {
                JSONObject c = cals.optJSONObject(i);
                if (c != null) a.calendars.add(new Cal(c.optString("url"), c.optString("name"), c.optBoolean("on", true)));
            }
        }
        return a;
    }
}
