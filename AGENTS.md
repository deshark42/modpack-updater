# AGENTS.md

modpack-updater is a Java 17 CLI that publishes a Minecraft modpack instance's tracked files to S3-compatible object storage and applies a release in place on another instance. A publisher packs a tag's `base/`, `overlay/` and `remove.txt`; a subscriber diffs release manifests and transfers only the chunks it does not already have.

Agent Notes are agent-written RFCs that record the rationale and the rejected alternatives behind a durable decision. The contract for this repository is [.agents/notes/README.md](.agents/notes/README.md); work under `.agents/notes/` also follows [.agents/notes/AGENTS.md](.agents/notes/AGENTS.md).

## Vocabulary

- **tag** — one publish stream (for example `client` or `server`); tags evolve independently.
- **release** — one immutable version of a tag, named by `release_id`.
- **manifest** — a release's complete path list, each file with its hash, size and chunk-table id.
- **chunk** — one content-defined slice of a file, addressed by the sha256 of its bytes.
- **chunk table** (`index`) — the ordered chunk list of one file, addressed by the sha256 of the table bytes.
- **blob** — a content-addressed bucket object: a chunk under `blob/`, or a chunk table under `index/`. The existence cache treats both as blobs.

## Repository layout

```
src/main/java/io/github/deshark42/modpackupdater/
  Main.java, UserError.java
  cli/        argument parsing and exit codes
  command/    init, publish, update
  model/      manifest, pointer, config, layout, chunk table, path rules
  fs/         chunking, hashing, atomic writes, tree scan
  s3/         S3 client (SigV4) and the blob existence cache
  json/       JSON reader/writer
  log/        terminal output and run transcripts
  util/       parallel execution
src/test/java/  JUnit 5; FakeS3 serves an in-process S3 endpoint
.agents/        Agent Notes and skills
scripts/        Agent Note gates
build.gradle.kts, gradle/   Java 17 build and Gradle wrapper
```

The runtime uses only the JDK: JSON, SigV4 and the HTTP client are implemented in-tree.

## Commands

```sh
npm run verify   # all Agent Note gates: path/class, header/sections, sealed archive
./gradlew test    # JUnit suite
./gradlew build   # compile, test, jar (build/libs/modpack-updater-<version>.jar)
```

The gates execute TypeScript directly, so Node 22.18 or newer is required. `package.json` owns the script inventory.

Usage:

```sh
java -jar build/libs/modpack-updater-<version>.jar init [--tag <tag>]
java -jar build/libs/modpack-updater-<version>.jar publish --tag <tag> --id <release-id>
java -jar build/libs/modpack-updater-<version>.jar update --tag <tag> [--id <release-id>]
```

All commands take `--workspace` and `--verbose`/`--quiet`; `publish` and `update` take `--dry-run`; `--cache-max-age-days` belongs to `publish`.

## Run relevant checks locally

Before pushing, follow [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md); report only the commands run.

- Never default to the full suite or repeat a passing check for commit or push. CI, once configured, owns exhaustive coverage.

## Conventions

- **Create Agent Notes only for durable decision rationale;** mechanical/local edits are exempt, including local UI changes ([scope](.agents/notes/README.md#when-to-write-one)). Archived notes are frozen: never edit or treat them as current authority ([archive policy](.agents/notes/README.md#archiving-and-deletion)).