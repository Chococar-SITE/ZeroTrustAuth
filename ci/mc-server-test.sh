#!/usr/bin/env bash
###############################################################################
# ci/mc-server-test.sh
#
# Real Minecraft (Paper) server execution test for the ZeroTrustAuth plugin.
#
# What this does, end to end:
#   1. Reads the target Minecraft version from gradle.properties (single source
#      of truth — never hardcode the MC version in CI).
#   2. Downloads the latest Paper build for that version via the PaperMC v2 API.
#   3. Prepares a throwaway server directory (eula, server.properties, plugin jar).
#   4. Boots a REAL Paper server headless, feeding console commands over a FIFO.
#   5. Waits for "Done (" (server fully started), then asserts the plugin's
#      startup self-test passed and exercises the `authkey enroll <uuid>` command.
#   6. Asserts there were no fatal plugin errors during enable.
#   7. Stops the server cleanly and prints a PASS/FAIL summary.
#
# Exit code: 0 only if every assertion passed; non-zero otherwise.
#
# Designed to run on a GitHub-hosted ubuntu runner (open internet, JDK 21).
# It is intentionally self-contained and heavily commented because the team
# will maintain it. The marker strings it asserts on (see ASSERTION MARKERS
# below) are a contract with the plugin — keep them in sync.
#
# DO NOT run locally without network access: it downloads Paper from the
# PaperMC API.
###############################################################################

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# ASSERTION MARKERS — the contract between this test and the plugin.
# If the plugin changes any of these log/console strings, update them here too.
#   * On enable, the plugin MUST log a line containing exactly SELF_TEST_PASS
#     when the startup self-test succeeds (see core SelfTest.Report#summary()).
#   * It MUST log SELF_TEST_FAIL (and/or enter SAFE MODE) on failure — we assert
#     these are ABSENT.
#   * `authkey enroll <uuid>` from the console MUST print a line containing
#     "Enrollment code for <uuid>:".
# ─────────────────────────────────────────────────────────────────────────────
readonly SELF_TEST_PASS="SELF-TEST PASSED"
readonly SELF_TEST_FAIL="SELF-TEST FAILED"
readonly SAFE_MODE_MARKER="SAFE MODE"

# A fixed, obviously-fake UUID for the enroll smoke test (never a real player).
readonly TEST_UUID="00000000-0000-0000-0000-000000000001"

# Timeouts (seconds). Generous enough for a cold CI runner pulling vanilla
# assets on first boot, but bounded so a hang fails the job instead of stalling.
readonly STARTUP_TIMEOUT=150     # max wait for "Done (" after launch
readonly ENROLL_TIMEOUT=30       # max wait for the enroll-code line
readonly STOP_TIMEOUT=60         # max wait for clean shutdown after `stop`
# Outer guard around the JVM. MUST exceed the worst-case sum of the internal
# waits (STARTUP + ENROLL + STOP = 240s) plus headroom, so neither the FIFO
# holder nor the `timeout` ever fires before our own assertions/stop run. Kept
# well under the workflow's 20-minute job ceiling.
readonly HARD_KILL_TIMEOUT=420   # outer `timeout` guard around the JVM (seconds)

# ─────────────────────────────────────────────────────────────────────────────
# Paths. Resolve the repo root from this script's location so the harness works
# regardless of the caller's working directory (CI checks out into an arbitrary
# path, and bash background calls reset cwd between invocations).
# ─────────────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly SERVER_DIR="${REPO_ROOT}/run-server"
readonly SERVER_LOG="${SERVER_DIR}/server.log"
readonly CONSOLE_FIFO="${SERVER_DIR}/console_in"

# Globals populated during the run (used by cleanup).
SERVER_PID=""
FIFO_HOLDER_PID=""

# ─────────────────────────────────────────────────────────────────────────────
# Logging helpers — keep CI output skimmable.
# ─────────────────────────────────────────────────────────────────────────────
log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[1;32m[OK]\033[0m %s\n' "$*"; }
err()  { printf '    \033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; }

# Dump the tail of the server log — always called on any failure path so the
# CI logs (and the uploaded artifact) show why we failed.
dump_log_tail() {
  local n="${1:-120}"
  if [[ -f "${SERVER_LOG}" ]]; then
    printf '\n\033[1;33m──── last %s lines of server.log ────\033[0m\n' "${n}" >&2
    tail -n "${n}" "${SERVER_LOG}" >&2 || true
    printf '\033[1;33m──── end server.log ────\033[0m\n\n' >&2
  else
    err "no server.log to dump (${SERVER_LOG} missing)"
  fi
}

# Best-effort teardown: stop the JVM and close the FIFO writer. Registered on
# EXIT so a `set -e` abort anywhere still cleans up the background processes.
cleanup() {
  local rc=$?
  # If the server is still running, try a graceful stop first, then SIGKILL.
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    info "cleanup: stopping leftover server (pid ${SERVER_PID})"
    send_console "stop" || true
    # Give it a few seconds; we are on the way out so don't block long.
    for _ in $(seq 1 10); do
      kill -0 "${SERVER_PID}" 2>/dev/null || break
      sleep 1
    done
    kill -9 "${SERVER_PID}" 2>/dev/null || true
  fi
  # Release the process that holds the FIFO open for writing.
  if [[ -n "${FIFO_HOLDER_PID}" ]] && kill -0 "${FIFO_HOLDER_PID}" 2>/dev/null; then
    kill "${FIFO_HOLDER_PID}" 2>/dev/null || true
  fi
  exit "${rc}"
}
trap cleanup EXIT

# Write a single command to the server console via the FIFO.
send_console() {
  local cmd="$1"
  if [[ ! -p "${CONSOLE_FIFO}" ]]; then
    err "console FIFO missing; cannot send: ${cmd}"
    return 1
  fi
  info "console <- ${cmd}"
  # The FIFO has a long-lived writer (see launch_server), so this open/close
  # does NOT signal EOF to the JVM; it just appends one line.
  printf '%s\n' "${cmd}" > "${CONSOLE_FIFO}"
}

# Poll the server log until $1 (a fixed string) appears, or until $2 seconds
# elapse. Returns 0 on found, 1 on timeout. Uses grep -F (fixed string) so
# regex metacharacters in markers/UUIDs are treated literally.
wait_for_log() {
  local needle="$1" timeout_s="$2" waited=0
  while (( waited < timeout_s )); do
    if grep -qF -- "${needle}" "${SERVER_LOG}" 2>/dev/null; then
      return 0
    fi
    # Fail fast if the JVM already died — no point waiting out the full timeout.
    if [[ -n "${SERVER_PID}" ]] && ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      err "server process exited while waiting for: ${needle}"
      return 1
    fi
    sleep 1
    (( waited++ )) || true
  done
  return 1
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 1 — read the Minecraft version from gradle.properties.
# ─────────────────────────────────────────────────────────────────────────────
read_mc_version() {
  local props="${REPO_ROOT}/gradle.properties"
  [[ -f "${props}" ]] || { err "gradle.properties not found at ${props}"; exit 1; }
  # Match `minecraftVersion=...`, ignore comments/whitespace, take the value.
  MC_VERSION="$(grep -E '^[[:space:]]*minecraftVersion[[:space:]]*=' "${props}" \
                | head -n1 | cut -d'=' -f2- | tr -d '[:space:]')"
  readonly MC_VERSION
  [[ -n "${MC_VERSION}" ]] || { err "minecraftVersion missing/empty in gradle.properties"; exit 1; }
  ok "Minecraft version (from gradle.properties): ${MC_VERSION}"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 2 — download the latest Paper build for MC_VERSION via the PaperMC v2 API.
# Prefers a build whose channel is "default" (stable/recommended) over
# experimental builds; falls back to the newest build if none are "default".
# ─────────────────────────────────────────────────────────────────────────────
download_paper() {
  command -v jq   >/dev/null || { err "jq is required but not installed"; exit 1; }
  command -v curl >/dev/null || { err "curl is required but not installed"; exit 1; }

  local builds_url="https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}/builds"
  log "Querying Paper builds: ${builds_url}"

  local builds_json
  if ! builds_json="$(curl -fSL --retry 3 --retry-delay 2 "${builds_url}")"; then
    err "failed to query Paper builds for MC ${MC_VERSION} (is the version published on PaperMC?)"
    exit 1
  fi

  # Prefer the latest build on the "default" channel; fall back to the very
  # latest build of any channel if there is no default one.
  local build
  build="$(jq -r '[.builds[] | select(.channel == "default")] | last | .build // empty' <<<"${builds_json}")"
  if [[ -z "${build}" ]]; then
    info "no \"default\"-channel build found; falling back to latest available build"
    build="$(jq -r '.builds | last | .build // empty' <<<"${builds_json}")"
  fi
  [[ -n "${build}" && "${build}" != "null" ]] || { err "could not determine a Paper build number"; exit 1; }
  ok "selected Paper build: ${build}"

  local jar_name="paper-${MC_VERSION}-${build}.jar"
  local dl_url="https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}/builds/${build}/downloads/${jar_name}"
  PAPER_JAR="${SERVER_DIR}/paper.jar"
  readonly PAPER_JAR

  log "Downloading ${dl_url}"
  if ! curl -fSL --retry 3 --retry-delay 2 -o "${PAPER_JAR}" "${dl_url}"; then
    err "failed to download Paper jar"
    exit 1
  fi
  # Verify the jar is present and non-trivially sized (a stray HTML error page
  # would be tiny). Paper server jars are tens of MB.
  [[ -s "${PAPER_JAR}" ]] || { err "downloaded Paper jar is empty"; exit 1; }
  local size
  size="$(stat -c%s "${PAPER_JAR}" 2>/dev/null || wc -c <"${PAPER_JAR}")"
  if (( size < 1000000 )); then
    err "downloaded Paper jar is suspiciously small (${size} bytes) — likely not a real jar"
    exit 1
  fi
  ok "Paper jar downloaded: ${PAPER_JAR} (${size} bytes)"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 3 — locate the built plugin jar produced by the Gradle build step.
# Globs platform-paper/build/libs/*.jar and picks the one that is NOT the
# -sources jar (shadowJar sets classifier "" so the plain jar is the plugin).
# ─────────────────────────────────────────────────────────────────────────────
locate_plugin_jar() {
  local libs_dir="${REPO_ROOT}/platform-paper/build/libs"
  [[ -d "${libs_dir}" ]] || { err "plugin libs dir not found: ${libs_dir} (did the Gradle build run?)"; exit 1; }

  PLUGIN_JAR=""
  local f
  for f in "${libs_dir}"/*.jar; do
    [[ -e "${f}" ]] || continue                 # no-glob-match guard
    case "${f}" in
      *-sources.jar|*-javadoc.jar) continue ;;  # skip non-plugin artifacts
    esac
    PLUGIN_JAR="${f}"
    break
  done
  readonly PLUGIN_JAR
  [[ -n "${PLUGIN_JAR}" ]] || { err "no plugin jar found in ${libs_dir}"; dump_libs "${libs_dir}"; exit 1; }
  [[ -s "${PLUGIN_JAR}" ]] || { err "plugin jar is empty: ${PLUGIN_JAR}"; exit 1; }
  ok "plugin jar: ${PLUGIN_JAR}"
}

dump_libs() {
  err "contents of $1:"
  ls -l "$1" >&2 || true
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 3b — prepare the server directory (eula, server.properties, plugin).
# Keeps everything minimal & offline-friendly: flat world, no nether, tiny view
# distance, online-mode off (no Mojang auth in CI).
# We deliberately DO NOT write plugins/ZeroTrustAuth/config.yml — the plugin
# ships a default config via saveDefaultConfig(), whose defaults already satisfy
# the self-test (fail_closed=true, signature_domain set). Secrets come from env.
# ─────────────────────────────────────────────────────────────────────────────
prepare_server_dir() {
  log "Preparing server directory: ${SERVER_DIR}"
  rm -rf "${SERVER_DIR}"
  mkdir -p "${SERVER_DIR}/plugins"

  # Accept the Minecraft EULA (required for the server to start at all).
  printf 'eula=true\n' > "${SERVER_DIR}/eula.txt"

  # Minimal, fast, offline-friendly server.properties.
  # NOTE: in .properties files the ':' in level-type values must be escaped.
  cat > "${SERVER_DIR}/server.properties" <<'PROPS'
online-mode=false
level-type=minecraft\:flat
level-name=world
spawn-protection=0
max-players=5
view-distance=4
simulation-distance=4
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
generate-structures=false
enable-command-block=false
network-compression-threshold=-1
sync-chunk-writes=false
PROPS

  # Drop the built plugin jar into plugins/.
  cp "${PLUGIN_JAR}" "${SERVER_DIR}/plugins/"
  ok "copied plugin jar into plugins/"
  info "server.properties: flat world, online-mode=false, view-distance=4"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 4/5 — launch the Paper server headless with a controllable stdin.
#
# STDIN MECHANISM (why a FIFO):
#   Paper reads console commands from its stdin. We need to (a) keep stdin open
#   for the whole run so the JVM doesn't see EOF and shut its console reader,
#   and (b) inject commands at arbitrary times from this script.
#
#   We use a named pipe (FIFO):
#     * A long-lived background "holder" process opens the FIFO for writing
#       (fd 3) and then sleeps. As long as at least one writer has the FIFO
#       open, readers block on read instead of getting EOF — so the JVM's
#       console reader stays alive even when we're not actively sending.
#     * The JVM is started with `< console_in`, i.e. its stdin is the FIFO.
#     * To send a command we just append a line to the FIFO; the holder keeps
#       it open across those transient writes.
#
#   This is more robust in CI than a coproc or piping a fixed heredoc, because
#   it cleanly separates "keep stdin open" from "send command now", and lets us
#   send commands *after* polling the log for readiness (you can't time that
#   with a static `echo ... | java`).
#
#   An outer `timeout` is the final safety net so a wedged JVM can never hang
#   the CI job indefinitely.
# ─────────────────────────────────────────────────────────────────────────────
launch_server() {
  log "Launching Paper server (headless)"

  # Create the FIFO the JVM will read its console from.
  mkfifo "${CONSOLE_FIFO}"

  # Long-lived writer: hold the FIFO open for writing so the reader never hits
  # EOF. `sleep` in a subshell keeps fd 3 open; we kill it during cleanup.
  ( exec 3>"${CONSOLE_FIFO}"; sleep "${HARD_KILL_TIMEOUT}" ) &
  FIFO_HOLDER_PID=$!

  # Secrets / config via environment ONLY (never in files/commits):
  #   IP_HMAC_SECRET  — required, >= 16 bytes, or the self-test fails.
  #   DISCORD_BOT_TOKEN="" — Discord absent => WARNING only, not a failure.
  export IP_HMAC_SECRET="ci-zerotrust-test-hmac-secret-0001"
  export DISCORD_BOT_TOKEN=""

  # Start the JVM. We run from inside SERVER_DIR so Paper writes its world,
  # logs and plugin data there. stdin is the FIFO; stdout+stderr go to the log.
  # `-Dcom.mojang.eula.agree=true` is belt-and-braces alongside eula.txt.
  (
    cd "${SERVER_DIR}"
    exec timeout --signal=KILL "${HARD_KILL_TIMEOUT}s" \
      java -Xms512M -Xmx1500M \
        -Dcom.mojang.eula.agree=true \
        -Dpaper.disableChannelLimit=true \
        -jar "${PAPER_JAR}" nogui
  ) < "${CONSOLE_FIFO}" > "${SERVER_LOG}" 2>&1 &
  SERVER_PID=$!
  info "server JVM pid: ${SERVER_PID} (hard kill after ${HARD_KILL_TIMEOUT}s)"

  # Wait for full startup. Paper logs `Done (12.345s)! For help, type "help"`.
  log "Waiting up to ${STARTUP_TIMEOUT}s for server startup (\"Done (\")"
  if ! wait_for_log "Done (" "${STARTUP_TIMEOUT}"; then
    err "server did not finish starting within ${STARTUP_TIMEOUT}s"
    dump_log_tail 120
    exit 1
  fi
  ok "server started"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 6 — assertions on the startup self-test + the enroll command.
# ─────────────────────────────────────────────────────────────────────────────

# Tracks failures so we can report ALL problems rather than dying on the first.
FAILURES=0
fail_assert() { err "$*"; FAILURES=$((FAILURES + 1)); }

assert_self_test_passed() {
  log "Asserting startup self-test PASSED"
  if grep -qF -- "${SELF_TEST_PASS}" "${SERVER_LOG}"; then
    ok "found '${SELF_TEST_PASS}'"
  else
    fail_assert "expected '${SELF_TEST_PASS}' in server.log but it was not found"
  fi
  if grep -qF -- "${SELF_TEST_FAIL}" "${SERVER_LOG}"; then
    fail_assert "found '${SELF_TEST_FAIL}' in server.log (self-test failed)"
  else
    ok "no '${SELF_TEST_FAIL}'"
  fi
  if grep -qF -- "${SAFE_MODE_MARKER}" "${SERVER_LOG}"; then
    fail_assert "found '${SAFE_MODE_MARKER}' in server.log (plugin entered safe mode)"
  else
    ok "no '${SAFE_MODE_MARKER}'"
  fi
}

exercise_enroll_command() {
  log "Exercising console command: authkey enroll ${TEST_UUID}"
  # Bukkit/Paper console commands are entered WITHOUT a leading slash.
  send_console "authkey enroll ${TEST_UUID}"

  local needle="Enrollment code for ${TEST_UUID}:"
  if wait_for_log "${needle}" "${ENROLL_TIMEOUT}"; then
    ok "found enrollment-code output for ${TEST_UUID}"
  else
    fail_assert "did not see '${needle}' within ${ENROLL_TIMEOUT}s"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 7 — assert NO fatal plugin errors during enable.
# We look only for failures attributable to OUR plugin, to avoid false
# positives on unrelated vanilla/Paper warnings. We also scan for SEVERE-level
# stack traces referencing our package.
# ─────────────────────────────────────────────────────────────────────────────
assert_no_plugin_errors() {
  log "Asserting no fatal plugin errors during enable"

  # Paper logs this when a plugin's onEnable throws.
  if grep -qiE "Error occurred while enabling ZeroTrustAuth" "${SERVER_LOG}"; then
    fail_assert "Paper reported 'Error occurred while enabling ZeroTrustAuth'"
  else
    ok "no enable error for ZeroTrustAuth"
  fi

  # Paper logs this when it cannot load our jar (bad/missing plugin.yml, etc.).
  # Constrain to our jar so a different broken plugin wouldn't false-positive.
  if grep -qiE "Could not load 'plugins/.*[Zz]ero[Tt]rust" "${SERVER_LOG}"; then
    fail_assert "Paper could not load the ZeroTrustAuth plugin jar"
  else
    ok "plugin jar loaded"
  fi

  # SEVERE-level lines that reference our package indicate an uncaught failure
  # inside our code. (INFO/WARNING referencing the package are fine.)
  if grep -nE "(SEVERE|FATAL).*com\.chococar\.zerotrust" "${SERVER_LOG}" >/dev/null; then
    fail_assert "found SEVERE/FATAL log line(s) referencing com.chococar.zerotrust:"
    grep -nE "(SEVERE|FATAL).*com\.chococar\.zerotrust" "${SERVER_LOG}" | head -n 10 >&2 || true
  else
    ok "no SEVERE/FATAL lines referencing com.chococar.zerotrust"
  fi

  # A stack trace explicitly thrown from our package at any level is also a fail.
  if grep -nE "^\s+at com\.chococar\.zerotrust" "${SERVER_LOG}" >/dev/null; then
    # Only treat it as fatal if it co-occurs with an exception header nearby;
    # a lone "at ..." without an Exception is unusual, but be conservative and
    # flag any stack frame from our package.
    fail_assert "found stack-trace frame(s) in com.chococar.zerotrust:"
    grep -nE "^\s+at com\.chococar\.zerotrust" "${SERVER_LOG}" | head -n 10 >&2 || true
  else
    ok "no stack-trace frames from com.chococar.zerotrust"
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 8 — stop the server cleanly, with a kill fallback.
# ─────────────────────────────────────────────────────────────────────────────
stop_server() {
  log "Stopping server (clean shutdown)"
  send_console "stop"

  local waited=0
  while (( waited < STOP_TIMEOUT )); do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      ok "server exited cleanly after ${waited}s"
      # Wait reaps the child and yields its exit status.
      wait "${SERVER_PID}" 2>/dev/null || true
      SERVER_PID=""   # mark stopped so cleanup() won't re-kill
      return 0
    fi
    sleep 1
    (( waited++ )) || true
  done

  err "server did not stop within ${STOP_TIMEOUT}s; sending SIGKILL"
  kill -9 "${SERVER_PID}" 2>/dev/null || true
  wait "${SERVER_PID}" 2>/dev/null || true
  SERVER_PID=""
  # A forced kill on shutdown is itself a problem worth flagging, but it does
  # not by itself invalidate the earlier assertions; record it as a failure.
  fail_assert "server required SIGKILL to stop"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 9 — final summary.
# ─────────────────────────────────────────────────────────────────────────────
summary() {
  echo
  echo "============================================================"
  if (( FAILURES == 0 )); then
    printf '\033[1;32m  MC SERVER TEST: PASS\033[0m  (MC %s, Paper build downloaded)\n' "${MC_VERSION}"
    echo "============================================================"
  else
    printf '\033[1;31m  MC SERVER TEST: FAIL\033[0m  (%d failed assertion(s))\n' "${FAILURES}"
    echo "============================================================"
    dump_log_tail 120
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────────────
main() {
  log "ZeroTrustAuth — Minecraft server execution test"
  read_mc_version
  locate_plugin_jar
  prepare_server_dir     # create the clean server dir FIRST (it rm -rf's the dir)
  download_paper         # ...then download Paper into it (dir must exist for curl -o)
  launch_server          # exits the script if the server never starts

  assert_self_test_passed
  exercise_enroll_command
  assert_no_plugin_errors

  stop_server
  summary

  (( FAILURES == 0 )) || exit 1
}

main "$@"
