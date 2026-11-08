/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
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

// Copied from car-notification-service (com.smartcar.notify.LocalAdb), GPL-3.0 like it.

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A shell on this very unit through its own adb daemon on 127.0.0.1 — the way the Monjaro
 * community tools (MConfig+) get root: an app may not run {@code su} itself (exec is denied), but
 * the adb shell user may, and the daemon listens on the loopback once adb over the network is
 * switched on, as those tools already require. The first connection makes the unit ask "allow
 * debugging?" for this app's key; allowed with "always", later connections are silent.
 *
 * <p>Just enough of the adb wire protocol to open one {@code shell:} stream per connection:
 * CNXN, AUTH (signature, then the public key), OPEN, WRTE/OKAY, CLSE. Blocking; call it off the
 * main thread.
 */
final class LocalAdb implements Closeable {
    static final int[] PORTS = {5555, 5556, 5557, 5037};

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int LOCAL_ID = 1;
    /** How long the driver has to answer "allow debugging?" on the first connection. */
    private static final int AUTH_TIMEOUT_MS = 60_000;

    /** DER prefix of a SHA-1 DigestInfo: adb signs its 20-byte token as if it were the digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Output {
        /** Called on the reading thread with each chunk the command prints. */
        void onOutput(String text);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    private LocalAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * Connects and authenticates on the first port that speaks the adb transport. A port that
     * accepts but is something else (an emulator's adbd has the adb-server protocol on 5037 and
     * closes on CNXN) is passed over, not the end. route-suggest change: the original stopped at
     * the first failed handshake.
     */
    static LocalAdb connect(Context context, StringBuilder report) throws IOException {
        KeyPair keys = keyPair(context);
        java.util.Map<String, StringBuilder> why = new java.util.LinkedHashMap<>();
        for (int port : PORTS) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            } catch (IOException e) {
                DebugLog.w("adb", port + ": " + e);
                StringBuilder b = reason(why, e, false);
                b.append(b.length() > 0 ? "/" : "").append(port);
                closeQuietly(s);
                continue;
            }
            report.append("adb: connected to port ").append(port).append('\n');
            LocalAdb adb = new LocalAdb(s);
            try {
                adb.handshake(keys, report);
                return adb;
            } catch (IOException e) {
                adb.close();
                DebugLog.w("adb", port + " handshake: " + e);
                StringBuilder b = reason(why, e, true);
                b.append(b.length() > 0 ? "/" : "").append(port);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, StringBuilder> w : why.entrySet()) {
            sb.append(sb.length() > 0 ? ", " : "").append(w.getValue()).append(" — ").append(w.getKey());
        }
        throw new IOException("нет ADB по сети (" + sb + "). Включите «ADB по сети», как для MConfig+");
    }

    /** The ports that failed for the same plain reason, under that reason. */
    private static StringBuilder reason(java.util.Map<String, StringBuilder> why, IOException e,
                                        boolean afterConnect) {
        String r;
        if (e instanceof java.net.SocketTimeoutException) r = "нет ответа";
        else if (e instanceof java.io.EOFException) r = afterConnect ? "не adbd" : "закрыт";
        else if (String.valueOf(e.getMessage()).contains("ECONNREFUSED")
                || e instanceof java.net.ConnectException) r = "отказ";
        else r = Errors.text(e);
        return why.computeIfAbsent(r, k -> new StringBuilder());
    }

    private void handshake(KeyPair keys, StringBuilder report) throws IOException {
        socket.setSoTimeout(AUTH_TIMEOUT_MS);
        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Message m = read();
            if (m.command == A_CNXN) {
                report.append("adb: authorised (").append(new String(m.data, StandardCharsets.UTF_8).trim())
                        .append(")\n");
                socket.setSoTimeout(0);
                return;
            }
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) {
                throw new IOException("adb: unexpected 0x" + Integer.toHexString(m.command));
            }
            if (!signed) {
                send(A_AUTH, AUTH_SIGNATURE, 0, sign((RSAPrivateKey) keys.getPrivate(), m.data));
                signed = true;
            } else {
                // Our key is not known yet: offer it, and the unit asks the driver.
                report.append("adb: key not yet allowed, asking on the screen\n");
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, androidPublicKey((RSAPublicKey) keys.getPublic()));
            }
        }
    }

    /** Runs {@code command} in an adb shell until it ends or the connection is closed. */
    void shell(String command, Output output) throws IOException {
        send(A_OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remoteId = 0;
        while (true) {
            Message m = read();
            switch (m.command) {
                case A_OKAY:
                    remoteId = m.arg0;
                    break;
                case A_WRTE:
                    send(A_OKAY, LOCAL_ID, m.arg0, new byte[0]);
                    output.onOutput(new String(m.data, StandardCharsets.UTF_8));
                    break;
                case A_CLSE:
                    if (remoteId != 0) {
                        send(A_CLSE, LOCAL_ID, remoteId, new byte[0]);
                    }
                    return;
                default:
                    break;
            }
        }
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    // --- wire format

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xff;
        }
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum)
                .putInt(~command);
        synchronized (out) {
            out.write(header.array());
            out.write(data);
            out.flush();
        }
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer header = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > MAX_DATA) {
            throw new IOException("adb: bad length " + length);
        }
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // --- keys

    private static KeyPair keyPair(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "adb");
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(
                        kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            writeAll(priv, kp.getPrivate().getEncoded());
            writeAll(pub, kp.getPublic().getEncoded());
            return kp;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("adb key: " + e, e);
        }
    }

    /** PKCS#1 v1.5 type-1 signature over SHA1-DigestInfo || token, padded by hand. */
    private static byte[] sign(RSAPrivateKey key, byte[] token) throws IOException {
        int size = (key.getModulus().bitLength() + 7) / 8;
        byte[] block = new byte[size];
        int tail = SHA1_DIGEST_INFO.length + token.length;
        block[0] = 0;
        block[1] = 1;
        for (int i = 2; i < size - tail - 1; i++) {
            block[i] = (byte) 0xff;
        }
        block[size - tail - 1] = 0;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, size - tail, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, size - token.length, token.length);
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            return c.doFinal(block);
        } catch (Exception e) {
            throw new IOException("adb sign: " + e, e);
        }
    }

    /**
     * The public key as adbd stores it: the old mincrypt RSAPublicKey struct (word count, n0inv,
     * modulus and R² mod n as little-endian 32-bit words, exponent), base64, then a name.
     */
    static byte[] androidPublicKey(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " route-suggest@unit\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger value, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            b.putInt(value.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }
}
