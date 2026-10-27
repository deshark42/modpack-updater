package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SigV4Test {

    /** The signature the GET example of the AWS Signature Version 4 documentation ends in. */
    private static final String DOCUMENTED = "AWS4-HMAC-SHA256"
            + " Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
            + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
            + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41";

    private static String sign(String canonicalQuery, Map<String, String> headers) {
        return SigV4.authorization("GET", "/test.txt", canonicalQuery, headers, SigV4.EMPTY_PAYLOAD,
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
    }

    private static Map<String, String> documentedHeaders() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_PAYLOAD);
        headers.put("x-amz-date", "20130524T000000Z");
        return headers;
    }

    /** The GET example from the AWS Signature Version 4 documentation. */
    @Test
    void signsTheDocumentedExample() {
        assertEquals(DOCUMENTED, sign("", documentedHeaders()));
    }

    @Test
    void signsTheSameRequestNoMatterHowItsHeadersAreOrderedOrPadded() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_PAYLOAD);
        headers.put("host", " examplebucket.s3.amazonaws.com ");

        assertEquals(DOCUMENTED, sign("", headers),
                "header names are sorted and their values trimmed before signing");
    }

    // Generated independently with Node's crypto SHA-256/HMAC, using the AWS example credentials.
    @ParameterizedTest(name = "query {0} produces signature {1}")
    @CsvSource({"'',df548e2ce037944d03f3e68682813b093763996d597cf890ca3d9037fd231eb4",
            "a=1&b=2,efd6d2cf9c730d3ce43d07dca0873f77f5694cf13c929641a214067a32aac198",
            "b=2&a=1,973901501b0ac0a0e9222c4177f691e3256644ff753bebf7a504940f1d5eae1c"})
    void theGivenQueryOrderIsPartOfTheSignature(String query, String signature) {
        Map<String, String> headers = documentedHeaders();
        headers.remove("range");

        String actual = sign(query, headers);

        assertEquals(SigV4.ALGORITHM
                + " Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature, actual);
    }
}
