#!/usr/bin/env node
// Selects the smallest local checks that cover the paths a push carries.
// AGENTS.md owns the rule; CI owns the exhaustive run and the platform matrix.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

interface Check {
    readonly label: string;
    readonly command: string;
    readonly args: string[];
}

const ROOT = fileURLToPath(new URL("..", import.meta.url));
const PACKAGE = "io.github.deshark42.modpackupdater";
const MAIN_PREFIX = "src/main/java/" + PACKAGE.replaceAll(".", "/") + "/";
const TEST_PREFIX = "src/test/java/" + PACKAGE.replaceAll(".", "/") + "/";
const WINDOWS = process.platform === "win32";

/** Files whose change can reach every test in the suite. */
const REPOSITORY_WIDE = [
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradlew",
    "gradlew.bat",
];
const REPOSITORY_WIDE_PREFIXES = ["gradle/"];
/** Shared test infrastructure: one edit there can change any test's behaviour. */
const SHARED_TEST_FILES = new Set([
    "TestFiles.java",
    "TestWorkspace.java",
    "TestLogs.java",
    "FakeS3.java",
    "SigV4Verifier.java",
]);
/** Test packages that exercise a production package besides its own. */
const PACKAGE_FILTERS: Record<string, string[]> = {
    model: ["model.*"],
    fs: ["fs.*", "command.*"],
    json: ["json.*"],
    log: ["log.*", "command.*", "cli.*"],
    s3: ["s3.*", "command.*", "cli.*"],
    util: ["util.*"],
    command: ["command.*", "cli.*"],
    cli: ["cli.*", "command.*"],
};

function git(args: string[]): string {
    return execFileSync("git", args, { cwd: ROOT, encoding: "utf8" }).trim();
}

function tryGit(args: string[]): string {
    try {
        return git(args);
    } catch {
        return "";
    }
}

function addLines(target: Set<string>, text: string): void {
    for (const line of text.split("\n")) {
        if (line.trim() !== "") {
            target.add(line.trim());
        }
    }
}

/** The commit this branch is compared against: its upstream, else origin/master, else nothing. */
function base(): string {
    for (const ref of ["@{u}", "origin/master"]) {
        if (tryGit(["rev-parse", "--verify", "--quiet", ref]) !== "") {
            return ref;
        }
    }
    return "";
}

function changedFiles(): string[] {
    const files = new Set<string>();
    const baseRef = base();
    if (baseRef !== "") {
        addLines(files, tryGit(["diff", "--name-only", baseRef + "...HEAD"]));
    }
    addLines(files, tryGit(["diff", "--name-only"]));
    addLines(files, tryGit(["diff", "--name-only", "--cached"]));
    addLines(files, tryGit(["ls-files", "--others", "--exclude-standard"]));
    return [...files].sort();
}

function gradleCheck(filters: string[] | null): Check {
    const args = ["test", "--console=plain"];
    for (const filter of filters ?? []) {
        args.push("--tests", PACKAGE + "." + filter);
    }
    return {
        label: filters === null ? "the whole JUnit suite" : filters.join(", "),
        command: WINDOWS ? "gradlew.bat" : "./gradlew",
        args,
    };
}

function isGateMaterial(file: string): boolean {
    return file.startsWith(".agents/")
        || file.startsWith("docs/")
        || file.startsWith("scripts/")
        || file === "package.json"
        || file.endsWith(".md");
}

function packageOf(file: string, prefix: string): string | null {
    if (!file.startsWith(prefix)) {
        return null;
    }
    const rest = file.slice(prefix.length);
    const slash = rest.indexOf("/");
    return slash === -1 ? null : rest.slice(0, slash);
}

function checksFor(files: string[]): Check[] {
    const checks: Check[] = [];
    let fullSuite = false;
    let gates = false;
    const filters = new Set<string>();

    for (const file of files) {
        if (isGateMaterial(file)) {
            gates = true;
        }
        if (REPOSITORY_WIDE.includes(file) || REPOSITORY_WIDE_PREFIXES.some((p) => file.startsWith(p))) {
            fullSuite = true;
        }
        if (SHARED_TEST_FILES.has(file.slice(file.lastIndexOf("/") + 1)) && file.startsWith(TEST_PREFIX)) {
            fullSuite = true;
        }
        for (const prefix of [MAIN_PREFIX, TEST_PREFIX]) {
            const name = packageOf(file, prefix);
            if (name === null) {
                continue;
            }
            const known = PACKAGE_FILTERS[name];
            if (known === undefined) {
                fullSuite = true;
            } else {
                for (const filter of known) {
                    filters.add(filter);
                }
            }
        }
    }

    if (gates) {
        checks.push({ label: "the Agent Note gates", command: "npm", args: ["run", "verify"] });
    }
    if (fullSuite) {
        checks.push(gradleCheck(null));
    } else if (filters.size > 0) {
        checks.push(gradleCheck([...filters].sort()));
    }
    return checks;
}

function run(check: Check): void {
    console.log("pre-push: " + check.label + ": " + check.command + " " + check.args.join(" "));
    execFileSync(check.command, check.args, {
        cwd: ROOT,
        stdio: "inherit",
        shell: WINDOWS,
    });
}

const argv = process.argv.slice(2);
let filesOverride: string[] | null = null;
let printOnly = false;
for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--files") {
        filesOverride = (argv[++i] ?? "").split(",").map((f) => f.trim()).filter((f) => f !== "");
    } else if (argv[i] === "--print") {
        printOnly = true;
    } else {
        throw new Error("unknown argument: " + argv[i]);
    }
}

const files = filesOverride ?? changedFiles();
const checks = checksFor(files);

if (files.length === 0) {
    console.log("pre-push: nothing changed, nothing to check");
} else if (checks.length === 0) {
    console.log("pre-push: " + files.length + " changed file(s) outside the checked surfaces");
} else {
    console.log("pre-push: " + files.length + " changed file(s), running " + checks.map((c) => c.label).join("; "));
    if (!printOnly) {
        for (const check of checks) {
            try {
                run(check);
            } catch {
                console.error("pre-push: " + check.label + " failed; fix it, or bypass explicitly with git push --no-verify");
                process.exit(1);
            }
        }
    }
}
