package io.github.deshark42.modpackupdater.security;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Ed25519 detached signatures of the exact, uncompressed manifest bytes. */
public final class ReleaseSigning {
    public static final int SIGNATURE_BYTES = 64;

    private ReleaseSigning() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new UserError("cannot generate an Ed25519 signing key", e);
        }
    }

    public static PrivateKey privateKey(Path file) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(file, "PRIVATE KEY")));
        } catch (GeneralSecurityException e) {
            throw new UserError("not an Ed25519 PKCS#8 private key: " + file, e);
        }
    }

    public static PublicKey publicKey(Path file) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(readPem(file, "PUBLIC KEY")));
        } catch (GeneralSecurityException e) {
            throw new UserError("not an Ed25519 public key: " + file, e);
        }
    }

    private static byte[] readPem(Path file, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        try {
            String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (!text.startsWith(begin) || !text.endsWith(end)) {
                throw new UserError("expected a PEM " + type + " in " + file);
            }
            String body = text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", "");
            return Base64.getDecoder().decode(body);
        } catch (IOException e) {
            throw new UserError("cannot read signing key " + file
                    + ": publishers need keygen; subscribers must install the publisher's trusted public key", e);
        } catch (IllegalArgumentException e) {
            throw new UserError("invalid PEM signing key: " + file, e);
        }
    }

    public static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }

    public static byte[] sign(byte[] manifest, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(manifest);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new UserError("cannot sign the release manifest with Ed25519", e);
        }
    }

    public static void verify(byte[] manifest, byte[] signature, PublicKey key) {
        if (signature.length != SIGNATURE_BYTES) {
            throw new UserError("invalid release signature: expected " + SIGNATURE_BYTES + " Ed25519 bytes");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifest);
            if (!verifier.verify(signature)) {
                throw new UserError("release signature does not match the trusted public key or manifest");
            }
        } catch (GeneralSecurityException e) {
            throw new UserError("invalid Ed25519 release signature", e);
        }
    }
}
