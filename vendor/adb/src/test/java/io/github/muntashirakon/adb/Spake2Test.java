// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0

package io.github.muntashirakon.adb;

import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.Test;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Spake2Test {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final byte[] CLIENT = "adb pair client ".getBytes(UTF_8);
    private static final byte[] SERVER = "adb pair server ".getBytes(UTF_8);

    private static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] o = new byte[n];
        for (int i = 0; i < n; i++) {
            o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return o;
    }

    /**
     * scalarMult(B, k) must produce the same point as BouncyCastle's (independently implemented)
     * Ed25519, where k = clamp(SHA-512(seed)[0..31]) is exactly the scalar BouncyCastle derives.
     * This anchors our Edwards group layer to a known-correct implementation.
     */
    @Test
    public void scalarMultiplicationMatchesBouncyCastle() {
        Spake2.Point base = Spake2.decode(unhex(
                "5866666666666666666666666666666666666666666666666666666666666666"));
        assertNotNull(base);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 20; i++) {
            byte[] seed = new byte[32];
            random.nextBytes(seed);
            byte[] expected = new byte[32];
            Ed25519.generatePublicKey(seed, 0, expected, 0);

            SHA512Digest sha = new SHA512Digest();
            sha.update(seed, 0, seed.length);
            byte[] hash = new byte[64];
            sha.doFinal(hash, 0);
            byte[] scalar = Arrays.copyOf(hash, 32);
            scalar[0] &= (byte) 248;
            scalar[31] &= (byte) 127;
            scalar[31] |= (byte) 64;

            assertArrayEquals(expected, Spake2.encode(Spake2.scalarMult(base, scalar)));
        }
    }

    /** The base point has order l, i.e. l * B is the identity element. */
    @Test
    public void basePointHasGroupOrder() {
        Spake2.Point base = Spake2.decode(unhex(
                "5866666666666666666666666666666666666666666666666666666666666666"));
        assertNotNull(base);
        byte[] l = unhex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");
        byte[] identity = new byte[32];
        identity[0] = 1;
        assertArrayEquals(identity, Spake2.encode(Spake2.scalarMult(base, l)));
    }

    /** Alice and Bob must derive the same 64-byte key from the same password. */
    @Test
    public void aliceAndBobAgree() {
        byte[] password = "a fairly long shared secret".getBytes(UTF_8);
        for (int i = 0; i < 20; i++) {
            Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
            Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
            byte[] aliceMsg = alice.generateMessage(password);
            byte[] bobMsg = bob.generateMessage(password);
            byte[] aliceKey = alice.processMessage(bobMsg);
            byte[] bobKey = bob.processMessage(aliceMsg);
            assertNotNull(aliceKey);
            assertNotNull(bobKey);
            assertEquals(Spake2.MAX_KEY_SIZE, aliceKey.length);
            assertArrayEquals("iteration " + i, aliceKey, bobKey);
        }
    }

    /** Mismatched passwords must not produce a shared key. */
    @Test
    public void mismatchedPasswordsDisagree() {
        Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        byte[] aliceMsg = alice.generateMessage("password one".getBytes(UTF_8));
        byte[] bobMsg = bob.generateMessage("password two".getBytes(UTF_8));
        assertFalse(Arrays.equals(alice.processMessage(bobMsg), bob.processMessage(aliceMsg)));
    }

    /** A peer message that is not a valid curve point must be rejected with a null key. */
    @Test
    public void invalidPeerMessageReturnsNull() {
        Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        alice.generateMessage("pw".getBytes(UTF_8));
        assertNull(alice.processMessage(new byte[16]));         // wrong length

        // Find an encoding whose y-coordinate yields a non-square (i.e. not on the curve).
        byte[] notOnCurve = null;
        for (int b = 1; b < 256 && notOnCurve == null; b++) {
            byte[] candidate = new byte[32];
            candidate[0] = (byte) b;
            if (Spake2.decode(candidate) == null) {
                notOnCurve = candidate;
            }
        }
        assertNotNull(notOnCurve);
        assertNull(alice.processMessage(notOnCurve));
    }

    /**
     * Known-answer regression vector with fixed ephemeral keys. Locks the exact wire output so any
     * accidental change to the algorithm is caught. The values were produced by this implementation
     * and cross-checked for self-consistency and against BoringSSL's curve constants.
     */
    @Test
    public void deterministicVector() {
        byte[] password = unhex("353932373831E63DD959651C211600F3B6561D0B9D90AF09D0A4A453EE2059A4"
                + "80CC7C5A94D4D48933F9FFF5FE43317D52FA7BFF8F8BC4F3488B8007330FEC7C7EDC91C20E5D");
        byte[] aliceEphemeral = unhex("47f6c458e5f062db8427d2d9bb20c954a76d6943959756a18d11d45e"
                + "1ad190f980a86d185a93ca1d3025c5febe3aac4045b34a39b1f511385ca97fc4332137f3");
        byte[] bobEphemeral = unhex("a6bf9f9bf7819e0ded8c2dd82a1aa38acb2f8a6403429cff33d64ea9c4"
                + "0439d5fd7029811a5f5a8f7c89c8b44ac0b421f6b24ca2ba18d2069995831730cd8c5a");

        Spake2 alice = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
        Spake2 bob = new Spake2(Spake2.Role.BOB, SERVER, CLIENT);
        byte[] aliceMsg = alice.generateMessage(password, aliceEphemeral.clone());
        byte[] bobMsg = bob.generateMessage(password, bobEphemeral.clone());
        byte[] aliceKey = alice.processMessage(bobMsg);
        byte[] bobKey = bob.processMessage(aliceMsg);

        assertArrayEquals(unhex("135d85fa69022bbc7445653e19047e5b6981aa5b9d309b0de6d2e704dfe1568c"), aliceMsg);
        assertArrayEquals(unhex("d5bd4ead287e42f0a073adcb8dc46acc0630c4925fe1d43350e7441a8b29e03a"), bobMsg);
        assertArrayEquals(unhex("c6aa9dd57cb181e3e855ec36d2dd8d5a8c7b2d60fc7ebca9469188f8e67f7181"
                + "39c13dc9e66bd13a092f65df97059d3182e39c914ab797d2b8b215bb682583b1"), aliceKey);
        assertArrayEquals(aliceKey, bobKey);
    }

    /** End-to-end pairing-cipher round-trip using the real client/server names. */
    @Test
    public void pairingAuthCtxRoundTrip() {
        byte[] password = "device pairing code".getBytes(UTF_8);
        PairingAuthCtx alice = PairingAuthCtx.createAlice(password);
        PairingAuthCtx bob = PairingAuthCtx.createBob(password);
        assertNotNull(alice);
        assertNotNull(bob);

        assertTrue(alice.initCipher(bob.getMsg()));
        assertTrue(bob.initCipher(alice.getMsg()));

        byte[] plaintext = "hello, peer".getBytes(UTF_8);
        byte[] decrypted = bob.decrypt(alice.encrypt(plaintext));
        assertArrayEquals(plaintext, decrypted);
    }
}
