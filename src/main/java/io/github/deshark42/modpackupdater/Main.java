package io.github.deshark42.modpackupdater;

import io.github.deshark42.modpackupdater.cli.Cli;
import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(Cli.run(args, Path.of("").toAbsolutePath(), System.out, System.err));
    }
}
