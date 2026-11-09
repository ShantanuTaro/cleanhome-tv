package com.gathin.tvhome;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * Runs one shell command through the TV's own adb port. An ordinary app may only close itself on patched Android, so
 * Close All needs the shell. The first use shows the TV's "Allow debugging?" prompt; the key never leaves the keystore.
 */
final class LocalShell {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, CLSE = 0x45534c43,
            WRTE = 0x45545257, OKAY = 0x59414b4f;
    private static final int LOCAL_ID = 1;
    private static final String KEY_ALIAS = "local_shell";
    private static final int ADB_PORT = 5555;
    /** Long enough to answer the TV's prompt. */
    private static final int PROMPT_TIMEOUT_MS = 60_000;
    /** DER header of a SHA-1 digest: adbd signs its token as if it were one. */
    private static final byte[] SHA1_HEADER = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private LocalShell() {
    }

    /** Returns when the command has finished; throws if the port is closed or the prompt was refused. */
    static void run(String command) throws IOException, GeneralSecurityException {
        KeyPair key = key();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), 3000);
            socket.setSoTimeout(PROMPT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.US_ASCII));
            boolean signed = false;
            byte[] head = new byte[24];
            while (true) {
                in.readFully(head);
                ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                int type = h.getInt(), remote = h.getInt();
                h.getInt();
                byte[] data = new byte[h.getInt()];
                in.readFully(data);
                if (type == AUTH) {
                    if (signed) send(out, AUTH, 3, 0, publicKey((RSAPublicKey) key.getPublic()));
                    else send(out, AUTH, 2, 0, sign(key.getPrivate(), data));
                    signed = true;
                } else if (type == CNXN) {
                    send(out, OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.US_ASCII));
                } else if (type == WRTE) {
                    send(out, OKAY, LOCAL_ID, remote, new byte[0]);
                } else if (type == CLSE) {
                    return;
                } else if (type != OKAY) {
                    throw new EOFException("unexpected adb message");
                }
            }
        }
    }

    private static void send(OutputStream out, int type, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer m = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        m.putInt(type).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~type).put(data);
        out.write(m.array());
        out.flush();
    }

    private static KeyPair key() throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return new KeyPair(store.getCertificate(KEY_ALIAS).getPublicKey(), (PrivateKey) store.getKey(KEY_ALIAS, null));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build());
        return generator.generateKeyPair();
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("NONEwithRSA");
        signature.initSign(key);
        signature.update(SHA1_HEADER);
        signature.update(token);
        return signature.sign();
    }

    /** adb's own key layout: a little-endian mincrypt RSAPublicKey in base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n.modInverse(BigInteger.ONE.shiftLeft(32)).negate().intValue());
        putLittleEndian(b, n);
        putLittleEndian(b, BigInteger.ONE.shiftLeft(4096).mod(n));
        b.putInt(key.getPublicExponent().intValue());
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tv-home-screen\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void putLittleEndian(ByteBuffer out, BigInteger value) {
        byte[] bigEndian = value.toByteArray();
        for (int i = 0; i < 256; i++) out.put(i < bigEndian.length ? bigEndian[bigEndian.length - 1 - i] : 0);
    }
}
