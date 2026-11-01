package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.ReleaseIdentity;
import io.github.deshark42.modpackupdater.s3.S3Store;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Advances one channel to an existing release after verifying its signed identity at the source. */
public final class Promote {
    private static final Logger LOG = LoggerFactory.getLogger(Promote.class);

    private Promote() {
    }

    public static void run(Path workspace, String tag, String releaseId, String channel) {
        run(workspace, tag, releaseId, channel, Clock.systemUTC());
    }

    public static void run(Path workspace, String tag, String releaseId, String channel, Clock clock) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        Names.requireChannel(channel);
        Config config = Config.load(workspace);
        PublicKey trustedKey = ReleaseSigning.publicKey(config.publicKeyFile(workspace));
        S3Store store = new S3Store(config, clock);
        String releaseKey = ObjectKeys.release(tag, releaseId);
        byte[] manifestBytes = store.getBytes(releaseKey);
        byte[] signature = store.getBytes(ObjectKeys.signature(tag, releaseId));
        ReleaseSigning.verify(manifestBytes, signature, trustedKey);
        Manifest manifest;
        try {
            manifest = Manifest.parse(new String(manifestBytes, StandardCharsets.UTF_8), store.key(releaseKey));
        } catch (Json.JsonException e) {
            throw new UserError(store.key(releaseKey) + ": " + e.getMessage());
        }
        ReleaseIdentity.require(manifest, tag, releaseId);
        LOG.info("verified the release signature for tag {} release {}", tag, releaseId);

        store.putBytes(ObjectKeys.channel(tag, channel),
                new Pointer(releaseId).toJson().getBytes(StandardCharsets.UTF_8));
        LOG.info("promoted tag {} release {} to channel {}", tag, releaseId, channel);
    }
}
