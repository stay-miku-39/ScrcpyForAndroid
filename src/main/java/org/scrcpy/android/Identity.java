package org.scrcpy.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Stable host identity, encrypted by a non-exportable Android Keystore AES key. */
final class Identity {
    final PrivateKey key;
    final Certificate certificate;
    private static final String ALIAS = "scrcpy-adb-identity-v1";

    Identity(Context context) throws Exception {
        KeyStore androidKeys = KeyStore.getInstance("AndroidKeyStore");
        androidKeys.load(null);
        if (!androidKeys.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        SecretKey wrappingKey = (SecretKey) androidKeys.getKey(ALIAS, null);
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "adb-identity.enc"));
        if (file.getBaseFile().exists()) {
            try (DataInputStream in = new DataInputStream(file.openRead())) {
                byte[] iv = new byte[12]; in.readFully(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, iv));
                try (DataInputStream plain = new DataInputStream(new ByteArrayInputStream(cipher.doFinal(readAll(in))))) {
                    int length = plain.readInt();
                    if (length < 1 || length > 8192) throw new IOException("Invalid saved identity");
                    byte[] pkcs8 = new byte[length]; plain.readFully(pkcs8);
                    key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                    certificate = CertificateFactory.getInstance("X.509").generateCertificate(plain);
                }
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            java.security.KeyPair pair = generator.generateKeyPair(); key = pair.getPrivate();
            X500Name name = new X500Name("CN=Scrcpy Android");
            long now = System.currentTimeMillis();
            certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,
                    new BigInteger(128, new SecureRandom()), new Date(now - 86400000L), new Date(now + 315360000000L), name, pair.getPublic())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(key)));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream plain = new DataOutputStream(bytes);
            plain.writeInt(key.getEncoded().length); plain.write(key.getEncoded()); plain.write(certificate.getEncoded());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            FileOutputStream out = file.startWrite();
            try { out.write(cipher.getIV()); out.write(cipher.doFinal(bytes.toByteArray())); file.finishWrite(out); }
            catch (Exception e) { file.failWrite(out); throw e; }
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > 32768) throw new IOException("Saved identity too large");
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }
}
