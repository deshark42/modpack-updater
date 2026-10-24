package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Config;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The read side of a bucket behind a CDN: anonymous GETs to the config's read_endpoint, so no
 * signature has to survive the edge and a cached response is shared rather than credentialed.
 */
public final class S3ReadStore extends S3Connection {

    public S3ReadStore(Config config) {
        super(config, Duration.ofMinutes(30));
    }

    @Override
    URI rawUri(String objectKey) {
        String base = config.readEndpoint();
        try {
            return URI.create(base + "/" + encodePath(objectKey));
        } catch (IllegalArgumentException e) {
            throw new UserError("cannot address " + objectKey + " on " + base + ": " + e.getMessage());
        }
    }

    @Override
    String display(String objectKey) {
        return rawUri(objectKey).toString();
    }

    @Override
    String reachTarget() {
        return config.readEndpoint();
    }

    @Override
    HttpRequest read(String objectKey) {
        return builder("GET", objectKey, rawUri(objectKey), HttpRequest.BodyPublishers.noBody())
                .header("Accept-Encoding", "identity")
                .build();
    }

    @Override
    void checkDownload(String objectKey, HttpResponse<?> response) {
        response.headers().firstValue("content-encoding").ifPresent(encoding -> {
            if (!encoding.trim().equalsIgnoreCase("identity")) {
                throw new UserError("cannot read " + display(objectKey) + ": the response is " + encoding.trim()
                        + "-encoded, not the bytes that were stored");
            }
        });
    }
}
