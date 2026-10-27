package io.github.deshark42.modpackupdater;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Independently checks the SigV4 protocol at the fake HTTP boundary, including the transmitted body. */
public final class TestSigV4Verifier {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    public record Verdict(String errorCode, String message) {

        public static Verdict ok() {
            return new Verdict(null, "");
        }

        public static Verdict refused(String errorCode, String message) {
            return new Verdict(errorCode, message);
        }

        public boolean isValid() {
            return errorCode == null;
        }
    }

    private TestSigV4Verifier() {
    }

    public static Verdict verify(String method, String rawPath, String rawQuery,
            Map<String, String> headers, byte[] body, String accessKey, String secretKey,
            String region) {
        String authorization = headers.get("authorization");
        if (authorization == null || authorization.isBlank()) {
            return Verdict.refused("AccessDenied", "the request carries no Authorization header");
        }
        if (!authorization.startsWith(ALGORITHM + " ")) {
            return invalid("unknown signature algorithm in: " + authorization);
        }
        Map<String, String> fields = new TreeMap<>();
        for (String field : authorization.substring(ALGORITHM.length() + 1).split(",")) {
            String[] pair = field.trim().split("=", 2);
            if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) {
                return invalid("the Authorization header is malformed: " + authorization);
            }
        }
        String credential = fields.get("Credential");
        String signedNames = fields.get("SignedHeaders");
        String signature = fields.get("Signature");
        if (fields.size() != 3 || credential == null || signedNames == null || signature == null) {
            return invalid("the Authorization header is incomplete: " + authorization);
        }
        String[] scope = credential.split("/", -1);
        if (scope.length != 5 || !"s3".equals(scope[3]) || !"aws4_request".equals(scope[4])) {
            return invalid("the Authorization header has no bucket scope: " + authorization);
        }
        if (!scope[0].equals(accessKey)) {
            return Verdict.refused("AccessDenied", "it does not know the access key " + scope[0]);
        }
        if (!scope[2].equals(region)) {
            return Verdict.refused("AccessDenied",
                    "the request was signed for region " + scope[2] + ", not " + region);
        }
        String payloadHash = headers.get("x-amz-content-sha256");
        if (payloadHash == null) {
            return invalid("the request carries no x-amz-content-sha256 header");
        }
        String expectedPayload = sha256(body);
        if (!expectedPayload.equals(payloadHash)) {
            return invalid("x-amz-content-sha256 is " + payloadHash + " but the body hashes to " + expectedPayload);
        }
        Map<String, String> signed = new TreeMap<>();
        for (String name : signedNames.split(";", -1)) {
            String value = headers.get(name);
            if (value == null) {
                return invalid("the signed header " + name + " is missing from the request");
            }
            if (signed.put(name, value) != null) {
                return invalid("the signed header " + name + " occurs more than once");
            }
        }
        for (String required : new String[] {"host", "x-amz-date", "x-amz-content-sha256"}) {
            if (!signed.containsKey(required)) {
                return invalid("the required header " + required + " is not signed");
            }
        }
        if (!String.join(";", signed.keySet()).equals(signedNames)) {
            return invalid("the signed headers are not in canonical order");
        }
        String dateTime = signed.get("x-amz-date");
        if (!dateTime.matches("[0-9]{8}T[0-9]{6}Z") || !dateTime.substring(0, 8).equals(scope[1])) {
            return invalid("x-amz-date does not match the credential date");
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':')
                .append(value.trim().replaceAll("\\s+", " ")).append('\n'));
        String request = method + "\n" + rawPath + "\n" + (rawQuery == null ? "" : rawQuery) + "\n"
                + canonicalHeaders + "\n" + signedNames + "\n" + payloadHash;
        String credentialScope = String.join("/", scope[1], scope[2], scope[3], scope[4]);
        String toSign = ALGORITHM + "\n" + dateTime + "\n" + credentialScope + "\n"
                + sha256(request.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), scope[1]);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String expectedSignature = HexFormat.of().formatHex(hmac(key, toSign));
        if (!expectedSignature.equals(signature)) {
            return invalid("the signature does not match the request it was sent with");
        }
        return Verdict.ok();
    }

    private static Verdict invalid(String message) {
        return Verdict.refused("SignatureDoesNotMatch", message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new AssertionError("HmacSHA256 must be available", e);
        }
    }
}
