package org.scrcpy.android;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

final class AdbManager extends AbsAdbConnectionManager {
    private final Identity identity;
    AdbManager(Identity identity) {
        this.identity = identity;
        // Negotiate modern packet limits and TLS, independently of the host OS version.
        setApi(36); setTimeout(30, TimeUnit.SECONDS);
    }
    @Override protected PrivateKey getPrivateKey() { return identity.key; }
    @Override protected Certificate getCertificate() { return identity.certificate; }
    @Override protected String getDeviceName() { return "ScrcpyAndroid@android"; }
    String fingerprint() {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.certificate.getPublicKey().getEncoded());
            StringBuilder text = new StringBuilder();
            for (byte value : digest) { if (text.length() > 0) text.append(':'); text.append(String.format(java.util.Locale.ROOT, "%02X", value & 255)); }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
