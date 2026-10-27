package io.github.deshark42.modpackupdater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestSigV4VerifierTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    // AWS's published GET /test.txt SigV4 vector, independent of the production signer.
    private static Map<String, String> request() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("authorization", "AWS4-HMAC-SHA256"
                + " Credential=" + ACCESS_KEY + "/20130524/" + REGION + "/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        return headers;
    }

    private static TestSigV4Verifier.Verdict verify(Map<String, String> headers, byte[] body) {
        return TestSigV4Verifier.verify("GET", "/test.txt", "", headers, body, ACCESS_KEY, SECRET_KEY, REGION);
    }

    @Test
    void theDocumentedRequestIsAccepted() {
        TestSigV4Verifier.Verdict verdict = verify(request(), new byte[0]);

        assertEquals(TestSigV4Verifier.Verdict.ok(), verdict);
    }

    @Test
    void aGetWithAnUndeclaredBodyIsRefused() {
        TestSigV4Verifier.Verdict verdict = verify(request(), TestFiles.bytes("unexpected body"));

        assertFalse(verdict.isValid());
        assertEquals("SignatureDoesNotMatch", verdict.errorCode());
        assertTrue(verdict.message().contains("hashes to"), verdict.message());
    }

    @Test
    void aTamperedSignatureIsRefused() {
        Map<String, String> headers = request();
        String signed = headers.get("authorization");
        headers.put("authorization", signed.substring(0, signed.length() - 1) + "0");

        TestSigV4Verifier.Verdict verdict = verify(headers, new byte[0]);

        assertFalse(verdict.isValid());
        assertEquals("SignatureDoesNotMatch", verdict.errorCode());
        assertTrue(verdict.message().contains("does not match"), verdict.message());
    }

    @ParameterizedTest(name = "missing {0} is refused as {1}")
    @CsvSource({"authorization,AccessDenied,Authorization", "x-amz-content-sha256,SignatureDoesNotMatch,x-amz-content-sha256",
            "range,SignatureDoesNotMatch,range", "x-amz-date,SignatureDoesNotMatch,x-amz-date"})
    void aMissingRequiredHeaderIsRefused(String header, String code, String reason) {
        Map<String, String> headers = request();
        headers.remove(header);

        TestSigV4Verifier.Verdict verdict = verify(headers, new byte[0]);

        assertFalse(verdict.isValid());
        assertEquals(code, verdict.errorCode());
        assertTrue(verdict.message().contains(reason), verdict.message());
    }
}
