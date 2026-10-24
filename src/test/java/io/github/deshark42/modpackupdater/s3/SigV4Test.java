package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

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

    @Test
    void signsTheQueryStringAsPartOfTheRequest() {
        Map<String, String> headers = documentedHeaders();
        headers.remove("range");

        assertNotEquals(sign("a=1&b=2", headers), sign("b=2&a=1", headers),
                "the query string is signed in the order it is given");
        assertNotEquals(sign("a=1&b=2", headers), sign("", headers), "a query changes the signature");
    }
}
