package io.github.deshark42.modpackupdater.s3;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** AWS Signature Version 4 for S3 requests. */
final class SigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String EMPTY_PAYLOAD = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SigV4() {
    }

    /**
     * Builds the Authorization header for a request. {@code headers} carries every header that is
     * signed, including host, x-amz-content-sha256 and x-amz-date, with lower case names.
     */
    static String authorization(String method, String canonicalUri, String canonicalQuery,
            Map<String, String> headers, String payloadHash, String accessKey, String secretKey, String region) {
        Map<String, String> sorted = new TreeMap<>(headers);
        String amzDate = sorted.get("x-amz-date");
        if (amzDate == null || amzDate.length() < 8) {
            throw new IllegalArgumentException("x-amz-date is required");
        }
        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : sorted.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));
        byte[] signingKey = signingKey(secretKey, date, region);
        String signature = hex(hmac(signingKey, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static byte[] signingKey(String secretKey, String date, String region) {
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        return hmac(key, "aws4_request");
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
