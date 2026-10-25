package io.github.deshark42.modpackupdater;

import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Path;

/** Fixture workspace: the config that points a command at a FakeS3, and the tag layout it reads. */
public final class TestWorkspace {

    /** The bucket, prefix and credentials {@link #configure} writes, so tests and FakeS3 share one source. */
    public static final String BUCKET = "pack";
    public static final String PREFIX = "gtnh";
    public static final String ACCESS_KEY = "AK";
    public static final String SECRET_KEY = "SK";
    public static final String REGION = "us-east-1";

    private TestWorkspace() {
    }

    /** The config of a workspace pointed at this store: writes and reads both go straight to it. */
    public static Config config(FakeS3 s3) {
        return config(s3.url(), null);
    }

    /**
     * A path-style config with the fixture's bucket, prefix and credentials, whose reads go through
     * {@code readEndpoint} when one is given.
     */
    public static Config config(String endpoint, String readEndpoint) {
        return new Config(endpoint, readEndpoint, REGION, BUCKET, PREFIX, true, ACCESS_KEY, SECRET_KEY,
                Config.DEFAULT_CONCURRENCY);
    }

    public static void configure(Path workspace, FakeS3 s3) throws IOException {
        configure(workspace, s3.url(), null);
    }

    /**
     * Writes the config of a workspace whose writes go to {@code endpoint}, and whose reads, when
     * {@code readEndpoint} is given, are the URLs of a CDN in front of the same bucket.
     */
    public static void configure(Path workspace, String endpoint, String readEndpoint) throws IOException {
        String json = "{\"endpoint\": \"" + endpoint + "\", \"bucket\": \"" + BUCKET + "\", \"prefix\": \""
                + PREFIX + "\", \"path_style\": true, \"access_key\": \"" + ACCESS_KEY
                + "\", \"secret_key\": \"" + SECRET_KEY + "\"";
        if (readEndpoint != null) {
            json += ", \"read_endpoint\": \"" + readEndpoint + "\"";
        }
        TestFiles.write(workspace, Layout.STATE_DIR + "/" + Layout.CONFIG, json + "}");
        TestSigning.writeKeys(workspace);
    }

    /** The request path of a bucket object below the configured prefix, e.g. bucketPath("blob/ab/ab..."). */
    public static String bucketPath(String objectKey) {
        return "/" + BUCKET + "/" + PREFIX + "/" + objectKey;
    }

    public static void base(Path workspace, String tag, String name, String content) throws IOException {
        TestFiles.write(workspace, tag + "/" + Layout.BASE + "/" + name, content);
    }

    public static void overlay(Path workspace, String tag, String name, String content) throws IOException {
        TestFiles.write(workspace, tag + "/" + Layout.OVERLAY + "/" + name, content);
    }

    /** Writes a tag's remove.txt; one path per line, as the publisher reads it. */
    public static void remove(Path workspace, String tag, String content) throws IOException {
        TestFiles.write(workspace, tag + "/" + Layout.REMOVE, content);
    }
}
