package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the checks the fake store's refusal rests on: a request either adds up or is refused. */
class SigV4VerifierTest {

    private static final String PATH = TestWorkspace.bucketPath(ObjectKeys.latest("client"));
    private static final String DATE = "20130524T000000Z";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    /** The headers a client signs, before the signature itself is added. */
    private static Map<String, String> signedHeaders(String payloadHash) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", "127.0.0.1:9000");
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", DATE);
        return headers;
    }

    /** A request a real client would send: its signed headers plus the signature over them. */
    private static Map<String, String> request(String payloadHash, byte[] body) {
        Map<String, String> headers = signedHeaders(payloadHash);
        headers.put("authorization", SigV4.authorization("PUT", PATH, "", headers, payloadHash,
                TestWorkspace.ACCESS_KEY, TestWorkspace.SECRET_KEY, TestWorkspace.REGION));
        return headers;
    }

    private static SigV4Verifier.Verdict verify(Map<String, String> headers, byte[] body) {
        return SigV4Verifier.verify("PUT", PATH, "", headers, body, TestWorkspace.ACCESS_KEY,
                TestWorkspace.SECRET_KEY, TestWorkspace.REGION);
    }

    @Test
    void acceptsARequestThatAddsUp() {
        assertTrue(verify(request(Sha256.ofBytes(BODY), BODY), BODY).isValid());
    }

    @Test
    void refusesABodyThatIsNotTheOneTheHeaderHashes() {
        Map<String, String> headers = request(Sha256.ofBytes(BODY), BODY);
        byte[] other = "[]".getBytes(StandardCharsets.UTF_8);

        SigV4Verifier.Verdict verdict = verify(headers, other);

        assertFalse(verdict.isValid());
        assertEquals("SignatureDoesNotMatch", verdict.errorCode());
        assertTrue(verdict.message().contains("hashes to"), verdict.message());
    }

    @Test
    void refusesATamperedSignature() {
        Map<String, String> headers = request(Sha256.ofBytes(BODY), BODY);
        String signed = headers.get("authorization");
        String replacement = signed.endsWith("0") ? "1" : "0";
        headers.put("authorization", signed.substring(0, signed.length() - 1) + replacement);

        SigV4Verifier.Verdict verdict = verify(headers, BODY);

        assertFalse(verdict.isValid());
        assertEquals("SignatureDoesNotMatch", verdict.errorCode());
        assertTrue(verdict.message().contains("does not match"), verdict.message());
    }

    @Test
    void refusesARequestWithNoSignature() {
        SigV4Verifier.Verdict verdict = verify(signedHeaders(Sha256.ofBytes(BODY)), BODY);

        assertEquals("AccessDenied", verdict.errorCode());
        assertFalse(verdict.isValid());
    }
}
