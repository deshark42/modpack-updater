package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class SigV4Test {

    /** The GET example from the AWS Signature Version 4 documentation. */
    @Test
    void signsTheDocumentedExample() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", SigV4.EMPTY_PAYLOAD);
        headers.put("x-amz-date", "20130524T000000Z");

        assertEquals("AWS4-HMAC-SHA256"
                        + " Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                        + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                        + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                SigV4.authorization("GET", "/test.txt", "", headers, SigV4.EMPTY_PAYLOAD,
                        "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1"));
    }
}
