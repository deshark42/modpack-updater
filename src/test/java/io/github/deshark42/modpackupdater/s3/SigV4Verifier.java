package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.fs.Sha256;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recomputes the SigV4 signature a request carries, so the fake store can refuse one that does not
 * add up. It lives next to {@link SigV4}, whose helpers are package-private: signing and checking a
 * signature have to share one implementation, or a bug in either would go unnoticed.
 */
public final class SigV4Verifier {

    /** Why a request cannot be served: the S3 error code and the message for the error body. */
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

    private SigV4Verifier() {
    }

    /**
     * Checks one request: that it carries an Authorization header, that the header names the
     * configured access key and region, that its signature recomputes from the request's own path,
     * query, signed headers and payload hash, and that the payload hash matches the body.
     *
     * @param headers the request headers, with lower case names
     * @param body    the bytes the request carried: empty for GET and HEAD
     */
    public static Verdict verify(String method, String rawPath, String rawQuery,
            Map<String, String> headers, byte[] body, String accessKey, String secretKey,
            String region) {
        String authorization = headers.get("authorization");
        if (authorization == null || authorization.isBlank()) {
            return Verdict.refused("AccessDenied", "the request carries no Authorization header");
        }
        String prefix = SigV4.ALGORITHM + " ";
        if (!authorization.startsWith(prefix)) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "unknown signature algorithm in: " + authorization);
        }
        String fields = authorization.substring(prefix.length());
        String credential = field(fields, "Credential=");
        String signedHeaders = field(fields, "SignedHeaders=");
        String signature = field(fields, "Signature=");
        if (credential == null || signedHeaders == null || signature == null) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "the Authorization header is incomplete: " + authorization);
        }
        String[] scope = credential.split("/");
        if (scope.length != 5 || !"s3".equals(scope[3]) || !"aws4_request".equals(scope[4])) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "the Authorization header has no bucket scope: " + authorization);
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
            return Verdict.refused("SignatureDoesNotMatch",
                    "the request carries no x-amz-content-sha256 header");
        }
        String expectedPayload = "PUT".equals(method) ? Sha256.ofBytes(body) : SigV4.EMPTY_PAYLOAD;
        if (!expectedPayload.equals(payloadHash)) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "x-amz-content-sha256 is " + payloadHash + " but the body hashes to " + expectedPayload);
        }
        Map<String, String> signed = new LinkedHashMap<>();
        for (String name : signedHeaders.split(";")) {
            String value = headers.get(name);
            if (value == null) {
                return Verdict.refused("SignatureDoesNotMatch",
                        "the signed header " + name + " is missing from the request");
            }
            signed.put(name, value);
        }
        String recomputed;
        try {
            recomputed = SigV4.authorization(method, rawPath, rawQuery == null ? "" : rawQuery, signed,
                    payloadHash, accessKey, secretKey, region);
        } catch (IllegalArgumentException e) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "the signature cannot be recomputed: " + e.getMessage());
        }
        if (!recomputed.equals(authorization)) {
            return Verdict.refused("SignatureDoesNotMatch",
                    "the signature does not match the request it was sent with");
        }
        return Verdict.ok();
    }

    /** The value of one {@code name=value} field of an Authorization header, or null if absent. */
    private static String field(String fields, String name) {
        int start = fields.indexOf(name);
        if (start < 0) {
            return null;
        }
        start += name.length();
        int end = fields.indexOf(", ", start);
        return end < 0 ? fields.substring(start) : fields.substring(start, end);
    }
}
