# AGENTS.md

modpack-updater is a Java 17 CLI that publishes a Minecraft modpack instance's tracked files to S3-compatible object storage and applies a release in place on another instance. A publisher merges a tag's `base/` files with archives under `base_pack/`, then applies `overlay/` and `remove.txt`; a subscriber diffs release manifests and transfers only the chunks it does not already have.

Agent Notes are agent-written RFCs that record the rationale and the rejected alternatives behind a durable decision. The contract for this repository is [.agents/notes/README.md](.agents/notes/README.md); work under `.agents/notes/` also follows [.agents/notes/AGENTS.md](.agents/notes/AGENTS.md).

## Vocabulary

- **tag** — one publish stream (for example `client` or `server`); tags evolve independently.
- **release** — one immutable version of a tag, named by `release_id`, unique across that tag’s channels.
- **channel** — an invocation-selected pointer to a tag’s release (default `latest`), not a separate release or local-state identity.
- **manifest** — a release's complete path list, each file with its hash, size and chunk-table id.
- **chunk** — one content-defined slice of a file, addressed by the sha256 of its bytes.
- **chunk table** (`index`) — the ordered chunk list of one file, addressed by the sha256 of the table bytes.
- **blob** — a content-addressed bucket object: a chunk under `blob/`, or a chunk table under `index/`. The existence cache treats both as blobs.

## Repository layout

```
src/main/java/io/github/deshark42/modpackupdater/
  Main.java, UserError.java
  cli/        argument parsing and exit codes
  command/    init, publish, promote, update
  model/      manifest, pointer, config, layout, chunk table, path rules
  fs/         chunking, hashing, atomic writes, tree scan
  s3/         S3 client (SigV4) and the blob existence cache
  security/   Ed25519 release signing and local PEM keys
  json/       JSON reading and writing (Gson)
  log/        logging setup, console and transcript writers
  util/       parallel execution
src/test/java/  JUnit 5; FakeS3 serves an in-process S3 endpoint
.agents/        Agent Notes and skills
scripts/        Agent Note gates
build.gradle.kts, gradle/   Java 17 build and Gradle wrapper
```

SigV4 and the HTTP client are implemented in-tree; JSON is read and written through Gson and logging is SLF4J over tinylog, both shaded into the runnable jar.

## Commands

```sh
npm run verify   # all Agent Note gates: path/class, header/sections, sealed archive
npm run install-hooks  # make every push run the checks its files need
./gradlew test    # JUnit suite
./gradlew build   # compile, test, jar (build/libs/modpack-updater.jar)
```

The gates execute TypeScript directly, so Node 22.18 or newer is required. `package.json` owns the script inventory.

Usage:

```sh
java -jar build/libs/modpack-updater.jar init [--tag <tag>]
java -jar build/libs/modpack-updater.jar keygen
java -jar build/libs/modpack-updater.jar publish   # terminal wizard
java -jar build/libs/modpack-updater.jar publish --tag <tag> --id <release-id> [--channel <name> | --no-latest]
java -jar build/libs/modpack-updater.jar promote --tag <tag> --id <release-id> [--channel <name>]
java -jar build/libs/modpack-updater.jar update --tag <tag> [--channel <name>] [--id <release-id>]
java -jar build/libs/modpack-updater.jar update --tag <tag> --tag <tag> [--tag <tag> ...] [--channel <name>]
```

For `publish`, missing `--tag` or `--id` starts a wizard only when `System.console()` is available. It selects workspace, numbered tag and channel (latest plus locally remembered names, or a new name), reads the authoritative channel baseline with signature/identity verification, and shows the actual merged payload diff. Entering a new release id is the final confirmation and immediately publishes; a supplied id must be retyped. Cache age uses the default or option, without a prompt. `:q`, EOF or a blank final id cancels with exit code 0. Before confirmation there are no uploads, config fill-in, transcripts, blob-cache or channel-history writes; archive preparation may create owned temporary stages, cleaned on every exit (the temp parent may remain). Publishing reuses the prepared payload and checks configuration, channel pointer and retained file content for changes. Missing selected pointers mean first publication, but broken baseline metadata fails closed; pointerless releases do not fall back to latest. Provided workspace/tag/channel values skip their prompts. A complete tag/id pair preserves the noninteractive scripting path without baseline reads or a public-key requirement; without a console, missing values remain usage errors (exit 2). Other commands and the no-command entry stay unchanged.

All commands take `--workspace`; `--cache-max-age-days` and the valueless `--no-latest` flag belong to `publish`. `publish`, `promote` and `update` select `latest` by default or an arbitrary `--channel <name>`. Tags, release ids and channels use `[A-Za-z0-9][A-Za-z0-9._-]*`, at most 120 characters, case-sensitive. Publishing advances only the selected `metadata/<tag>/<channel>.json` pointer, after the complete manifest and signature; `latest` keeps its existing path with no migration. Release metadata and signatures are immutable and shared across channels. `--no-latest` uploads a pointerless signed release and cannot be combined with an explicit `--channel`. Channel selection is invocation-only: no persisted config or local current-state identity changes. After a successful CLI publish, promote or channel-based update, nondefault channel names are remembered per workspace/tag in `.modpack-updater/<tag>/channels.json` for wizard suggestions only; latest remains the first/default choice. Names are unique and sorted, not a server inventory or a saved default. Cancellation, failure, pointerless publish and update --id do not add history. Batch updates record each successful tag, even if a later tag fails; history errors warn without changing command success or overwriting malformed history. Beta is not private or a security boundary. The version is not part of the artifact name: `./gradlew build -PappVersion=<version>` (default `0.1.0`) writes it into the jar manifest as `Implementation-Version`.

For example: `publish --tag client --id 1.1.0-beta1 --channel beta`, then `update --tag client --channel beta`.

`promote` verifies an existing release at authoritative `endpoint` using the configured trusted public key, then writes only the selected channel pointer; it never uses `read_endpoint`, scans inputs, signs, reuploads immutable objects or fills config. It needs no private key or source channel, accepts pointerless releases, creates missing target pointers, and permits repeats and rollback. Metadata/signature/identity/read failures write no pointer. It does not audit referenced blobs or provide CAS, concurrency locks, freshness or rollback protection. CLI transcripts and best-effort target channel history are its only local writes. Tag/id are mandatory; no wizard, `--no-latest` or cache-age option.

For `update`, `--id` bypasses all channel lookups even when `--channel` is supplied; otherwise a missing selected channel fails without falling back to latest. Repeated `--tag` values share the selected channel and run sequentially in first-occurrence order, with duplicates skipped; all tag/id/channel arguments are validated before batch mutations. The first failure stops the batch; earlier successful tags are not rolled back. `--id` is only allowed with one distinct tag. This is not a merged subscription or an overlay: tag payload paths must not conflict, including file/directory prefixes.

Publisher setup, trusted public key distribution and mandatory release verification are documented in [README.md](README.md).

## Run relevant checks locally

Before pushing, follow [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md); report only the commands run. `npm run install-hooks` points git at `.githooks/pre-push`, which selects those checks from the files a push carries; `git push --no-verify` is the explicit bypass.

- Match evidence to the surface: the package tests a production change reaches, `npm run verify` for Agent Notes, docs and the gate scripts, and `./gradlew test` when build files or shared test fixtures change.
- Never default to the full suite or repeat a passing check for commit or push. CI owns exhaustive coverage and the platform matrix; rehearse everything locally only by explicit request, for CI diagnosis, or for an irreducibly repository-wide change.
- CI is the gate, not just the suite: [ci.yml](.github/workflows/ci.yml) runs `./gradlew build` on Linux and Windows plus the Agent Note gates. A push is not done until its run is green.
- A release is published only by [release.yml](.github/workflows/release.yml): pushing a `v<version>` tag whose commit is on master builds with `-PappVersion=<version>`, requires the jar manifest `Implementation-Version` to match the tag, and uploads `modpack-updater.jar` as the single release asset. Tags containing `-` become prereleases, the rest become latest. Never upload a locally built jar.

## Conventions

- **Create Agent Notes only for durable decision rationale;** mechanical/local edits are exempt, including local UI changes ([scope](.agents/notes/README.md#when-to-write-one)). Archived notes are frozen: never edit or treat them as current authority ([archive policy](.agents/notes/README.md#archiving-and-deletion)).

- **Testing policy** — [docs/testing.md](docs/testing.md).
