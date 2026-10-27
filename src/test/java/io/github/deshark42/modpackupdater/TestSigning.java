package io.github.deshark42.modpackupdater;

import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;

/** Public, non-secret RFC 8032 section 7.1 test vector 1; never a production signing identity. */
public final class TestSigning {
    private static final String PRIVATE_DER = "302e020100300506032b657004220420"
            + "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    private static final String PUBLIC_DER = "302a300506032b6570032100"
            + "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";

    private TestSigning() {
    }

    public static byte[] privateDer() {
        return HexFormat.of().parseHex(PRIVATE_DER);
    }

    public static byte[] publicDer() {
        return HexFormat.of().parseHex(PUBLIC_DER);
    }

    public static void writeKeys(Path workspace) throws IOException {
        TestFiles.write(workspace, Layout.DEFAULT_PRIVATE_KEY, ReleaseSigning.pem("PRIVATE KEY", privateDer()));
        TestFiles.write(workspace, Layout.DEFAULT_PUBLIC_KEY, ReleaseSigning.pem("PUBLIC KEY", publicDer()));
    }

    public static byte[] sign(byte[] bytes) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(privateDer()));
            return ReleaseSigning.sign(bytes, key);
        } catch (GeneralSecurityException e) {
            throw new AssertionError("the RFC Ed25519 test key must be valid", e);
        }
    }
}
