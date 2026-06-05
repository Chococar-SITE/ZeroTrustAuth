# ci/ — CI harness scripts

## `mc-server-test.sh` — real Paper server execution test

Boots a **real** Paper server with the freshly-built ZeroTrustAuth plugin and
asserts the plugin actually works at runtime. Driven by the
`.github/workflows/mc-server-test.yml` workflow on GitHub-hosted `ubuntu-latest`
runners (which have open internet and JDK 21 Temurin).

### What it does

1. Reads `minecraftVersion` from `gradle.properties` (single source of truth).
2. Downloads the latest Paper build for that version via the PaperMC v2 API
   (prefers the `default` channel, falls back to the newest build).
3. Prepares a throwaway `run-server/` dir: `eula=true`, a minimal flat-world
   `server.properties` (offline mode), and the built plugin jar in `plugins/`.
4. Launches Paper headless, feeding console commands over a named pipe (FIFO).
5. Waits for `Done (` (full startup), then asserts:
   - the startup self-test logged `SELF-TEST PASSED`,
   - it did **not** log `SELF-TEST FAILED` or `SAFE MODE`,
   - `authkey enroll <uuid>` prints `Enrollment code for <uuid>:`,
   - no fatal plugin errors (enable failure, load failure, or a
     `SEVERE`/`FATAL`/stack-trace line referencing `com.chococar.zerotrust`).
6. Stops the server cleanly (`stop`, with a SIGKILL fallback) and prints a
   PASS/FAIL summary. Exits non-zero on any failed assertion.

The plugin jar is built by the workflow with
`./gradlew :platform-paper:shadowJar`; the script just globs
`platform-paper/build/libs/*.jar` and skips `-sources`/`-javadoc` jars.

### Secrets / env

Secrets are injected via environment variables only (never files/commits), per
the project's security invariants:

- `IP_HMAC_SECRET` — set to a 32-char test value (must be ≥16 bytes or the
  self-test fails).
- `DISCORD_BOT_TOKEN=""` — Discord absent only produces a WARNING, not a
  self-test failure.

### Plugin contract — keep these in sync

The harness asserts on exact strings the plugin must log. If you rename any of
these, update the corresponding constants at the top of `mc-server-test.sh`:

| Marker (in script)            | Where the plugin must emit it                                  |
|-------------------------------|----------------------------------------------------------------|
| `SELF-TEST PASSED`            | logged on enable when the startup self-test passes             |
| `SELF-TEST FAILED` / `SAFE MODE` | logged on enable when the self-test fails (asserted ABSENT) |
| `Enrollment code for <uuid>:` | console output of `authkey enroll <uuid>`                      |

`SELF-TEST PASSED` / `SELF-TEST FAILED` come from
`core/.../auth/SelfTest.java` (`Report#summary()`); the Paper plugin must run
`SelfTest.run(...)` on enable and log that summary line. The `Enrollment code
for <uuid>:` line must be printed by the console handler for
`authkey enroll <uuid>` (`AuthEngine#enroll`).

> **Note:** these runtime pieces (the Paper plugin main class, `plugin.yml`, the
> bundled default `config.yml`, the `authkey` console command, and the
> self-test log emission) must exist for this test to pass. Until they are
> implemented, the job will fail at the "plugin enables / self-test" stage — by
> design, since that is exactly what the test guards.

### Running locally

Requires open internet (downloads Paper), `java` 21, `jq`, and `curl`. From the
repo root:

```bash
./gradlew :platform-paper:shadowJar
ci/mc-server-test.sh
```

Artifacts/log land in `run-server/` (gitignored-friendly; safe to delete).
