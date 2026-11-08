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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * NTLMv2 (MS-NLMP), client side, for on-premise Exchange: NEGOTIATE → the server's CHALLENGE →
 * AUTHENTICATE, all on one connection. No signing or sealing: only the HTTP authentication.
 * MD4 is written out here because Android's providers do not offer it.
 */
final class Ntlm {
    static final int NEGOTIATE_UNICODE = 0x00000001;
    static final int NEGOTIATE_OEM = 0x00000002;
    static final int REQUEST_TARGET = 0x00000004;
    static final int NEGOTIATE_NTLM = 0x00000200;
    static final int NEGOTIATE_ALWAYS_SIGN = 0x00008000;
    static final int NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000;
    static final int NEGOTIATE_TARGET_INFO = 0x00800000;
    static final int NEGOTIATE_128 = 0x20000000;
    static final int NEGOTIATE_56 = 0x80000000;
    static final int FLAGS = NEGOTIATE_UNICODE | NEGOTIATE_OEM | REQUEST_TARGET | NEGOTIATE_NTLM
            | NEGOTIATE_ALWAYS_SIGN | NEGOTIATE_EXTENDED_SESSIONSECURITY | NEGOTIATE_TARGET_INFO
            | NEGOTIATE_128 | NEGOTIATE_56;
    private static final byte[] SIGNATURE = "NTLMSSP\0".getBytes(StandardCharsets.US_ASCII);
    private static final int AV_EOL = 0;
    private static final int AV_TIMESTAMP = 7;

    private Ntlm() {
    }

    /** The «Authorization: NTLM …» value of the first message. */
    static String negotiate() {
        ByteBuffer b = le(32);
        b.put(SIGNATURE).putInt(1).putInt(FLAGS);
        b.putShort((short) 0).putShort((short) 0).putInt(32); // domain: none
        b.putShort((short) 0).putShort((short) 0).putInt(32); // workstation: none
        return Base64.getEncoder().encodeToString(b.array());
    }

    static final class Challenge {
        final int flags;
        final byte[] serverChallenge;
        final byte[] targetInfo;

        Challenge(int flags, byte[] serverChallenge, byte[] targetInfo) {
            this.flags = flags;
            this.serverChallenge = serverChallenge;
            this.targetInfo = targetInfo;
        }
    }

    /** The server's CHALLENGE from «WWW-Authenticate: NTLM …» (or «Negotiate …»), or null. */
    static Challenge challenge(String header) {
        if (header == null) return null;
        String h = header.trim();
        int sp = h.indexOf(' ');
        if (sp < 0) return null;
        String scheme = h.substring(0, sp);
        if (!scheme.equalsIgnoreCase("NTLM") && !scheme.equalsIgnoreCase("Negotiate")) return null;
        byte[] m;
        try {
            m = Base64.getDecoder().decode(h.substring(sp + 1).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (m.length < 32) return null;
        for (int i = 0; i < 8; i++) if (m[i] != SIGNATURE[i]) return null;
        ByteBuffer b = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(8) != 2) return null;
        int flags = b.getInt(20);
        byte[] ch = new byte[8];
        System.arraycopy(m, 24, ch, 0, 8);
        byte[] info = new byte[0];
        if (m.length >= 48) {
            int len = b.getShort(40) & 0xffff, off = b.getInt(44);
            if (off >= 0 && off + len <= m.length) {
                info = new byte[len];
                System.arraycopy(m, off, info, 0, len);
            }
        }
        return new Challenge(flags, ch, info);
    }

    /** The «Authorization: NTLM …» value of the AUTHENTICATE message. */
    static String authenticate(Challenge c, String user, String domain, String password) {
        byte[] client = new byte[8];
        new SecureRandom().nextBytes(client);
        return authenticate(c, user, domain, password, client, filetime(System.currentTimeMillis()));
    }

    static String authenticate(Challenge c, String user, String domain, String password,
                               byte[] clientChallenge, long filetime) {
        byte[] key = ntowfv2(user, domain, password);
        long serverTime = avTimestamp(c.targetInfo);
        long time = serverTime != 0 ? serverTime : filetime;
        ByteBuffer blob = le(28 + c.targetInfo.length + 4);
        blob.put((byte) 1).put((byte) 1).putShort((short) 0).putInt(0).putLong(time)
                .put(clientChallenge).putInt(0).put(c.targetInfo).putInt(0);
        byte[] proof = hmac(key, cat(c.serverChallenge, blob.array()));
        byte[] nt = cat(proof, blob.array());
        // With the server's timestamp the LM response must be zeros (MS-NLMP 3.1.5.1.2).
        byte[] lm = serverTime != 0 ? new byte[24]
                : cat(hmac(key, cat(c.serverChallenge, clientChallenge)), clientChallenge);

        byte[] dom = utf16(domain), usr = utf16(user), ws = utf16("");
        int off = 64;
        ByteBuffer b = le(off + lm.length + nt.length + dom.length + usr.length + ws.length);
        b.put(SIGNATURE).putInt(3);
        off = secbuf(b, lm.length, off);
        off = secbuf(b, nt.length, off);
        off = secbuf(b, dom.length, off);
        off = secbuf(b, usr.length, off);
        off = secbuf(b, ws.length, off);
        secbuf(b, 0, off); // session key: none
        b.putInt((c.flags & FLAGS) | NEGOTIATE_UNICODE);
        b.put(lm).put(nt).put(dom).put(usr).put(ws);
        return Base64.getEncoder().encodeToString(b.array());
    }

    private static int secbuf(ByteBuffer b, int len, int off) {
        b.putShort((short) len).putShort((short) len).putInt(off);
        return off + len;
    }

    private static long avTimestamp(byte[] info) {
        ByteBuffer b = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 4) {
            int id = b.getShort() & 0xffff, len = b.getShort() & 0xffff;
            if (id == AV_EOL || len > b.remaining()) break;
            if (id == AV_TIMESTAMP && len == 8) return b.getLong();
            b.position(b.position() + len);
        }
        return 0;
    }

    static long filetime(long unixMs) {
        return (unixMs + 11_644_473_600_000L) * 10_000L;
    }

    /** NTOWFv2: HMAC-MD5 of MD4(password) over UPPER(user) + domain, all UTF-16LE. */
    static byte[] ntowfv2(String user, String domain, String password) {
        return hmac(md4(utf16(password)), utf16(user.toUpperCase(Locale.ROOT) + domain));
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacMD5");
            m.init(new SecretKeySpec(key, "HmacMD5"));
            return m.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf16(String s) {
        return s.getBytes(StandardCharsets.UTF_16LE);
    }

    private static byte[] cat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    // ------------------------------------------------------------------ MD4 (RFC 1320)

    static byte[] md4(byte[] msg) {
        int n = ((msg.length + 8) / 64 + 1) * 64;
        byte[] p = new byte[n];
        System.arraycopy(msg, 0, p, 0, msg.length);
        p[msg.length] = (byte) 0x80;
        long bits = (long) msg.length * 8;
        for (int i = 0; i < 8; i++) p[n - 8 + i] = (byte) (bits >>> (8 * i));
        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        int[] x = new int[16];
        for (int blk = 0; blk < n; blk += 64) {
            for (int i = 0; i < 16; i++) {
                int o = blk + i * 4;
                x[i] = (p[o] & 0xff) | (p[o + 1] & 0xff) << 8 | (p[o + 2] & 0xff) << 16 | (p[o + 3] & 0xff) << 24;
            }
            int aa = a, bb = b, cc = c, dd = d;
            int[] s1 = {3, 7, 11, 19};
            for (int i = 0; i < 16; i++) {
                int f = (b & c) | (~b & d);
                int t = Integer.rotateLeft(a + f + x[i], s1[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            int[] s2 = {3, 5, 9, 13};
            for (int i = 0; i < 16; i++) {
                int k = (i % 4) * 4 + i / 4;
                int g = (b & c) | (b & d) | (c & d);
                int t = Integer.rotateLeft(a + g + x[k] + 0x5a827999, s2[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            int[] s3 = {3, 9, 11, 15};
            int[] order = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
            for (int i = 0; i < 16; i++) {
                int h = b ^ c ^ d;
                int t = Integer.rotateLeft(a + h + x[order[i]] + 0x6ed9eba1, s3[i % 4]);
                a = d; d = c; c = b; b = t;
            }
            a += aa;
            b += bb;
            c += cc;
            d += dd;
        }
        ByteBuffer out = le(16);
        out.putInt(a).putInt(b).putInt(c).putInt(d);
        return out.array();
    }
}
