#!/usr/bin/env bash
# Starts real Paper servers of the given versions with the built plugin, runs every command including a
# short benchmark, and checks the log for errors.
#
#   ./gradlew build && scripts/smoke-test.sh 1.20.6 1.21.4 26.3
#
# Server jars are downloaded from PaperMC (checksum verified) into run/smoke/<version>/ and reused.
# Requires: java (21+), curl, python3. Set JAVA=/path/to/java to run the servers on a specific JDK.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN_JAR="$(ls "$ROOT"/build/libs/TickCapacity-*.jar 2>/dev/null | head -1)"
UA="TickCapacity-smoke-test"
JAVA="${JAVA:-java}"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
[ -n "$PLUGIN_JAR" ] || { echo "Build first: ./gradlew build"; exit 2; }
[ $# -gt 0 ] || { echo "usage: $0 <paper-version>..."; exit 2; }

wait_for() { # file pattern timeout
  local i
  for ((i = 0; i < $3; i++)); do
    grep -qE "$2" "$1" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

overall=0
for V in "$@"; do
  DIR="$ROOT/run/smoke/$V"
  mkdir -p "$DIR/plugins/TickCapacity"
  if [ ! -f "$DIR/paper.jar" ]; then
    echo "[$V] downloading Paper..."
    meta="$(curl -fsSL -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$V/builds/latest")" || {
      echo "[$V] FAIL: unknown version"; overall=1; continue; }
    url="$(python3 -c 'import json,sys;print(json.load(sys.stdin)["downloads"]["server:default"]["url"])' <<<"$meta")"
    sha="$(python3 -c 'import json,sys;print(json.load(sys.stdin)["downloads"]["server:default"]["checksums"]["sha256"])' <<<"$meta")"
    curl -fsSL -A "$UA" -o "$DIR/paper.jar.part" "$url" || { echo "[$V] FAIL: download"; overall=1; continue; }
    actual="$(sha256 "$DIR/paper.jar.part")"
    [ "$actual" = "$sha" ] || { echo "[$V] FAIL: checksum mismatch"; rm -f "$DIR/paper.jar.part"; overall=1; continue; }
    mv "$DIR/paper.jar.part" "$DIR/paper.jar"
  fi
  cp "$PLUGIN_JAR" "$DIR/plugins/"
  echo "eula=true" >"$DIR/eula.txt"
  printf 'online-mode=false\nview-distance=4\nsimulation-distance=4\nserver-port=25601\nspawn-protection=0\n' \
    >"$DIR/server.properties"
  cat >"$DIR/plugins/TickCapacity/config.yml" <<'YAML'
language: en
sampling:
  interval-seconds: 5
  startup-grace-seconds: 0
benchmark:
  max-virtual-players: 4
  settle-seconds: 2
  stage-seconds: 5
  chunk-radius: 3
  keep-world: false
  progress-display: [bossbar, actionbar, tab]
YAML

  : >"$DIR/cmd.txt"
  rm -f "$DIR/server.log"
  (cd "$DIR" && tail -f cmd.txt | "$JAVA" -Xms1G -Xmx2G -jar paper.jar nogui >server.log 2>&1) &
  send() { echo "$1" >>"$DIR/cmd.txt"; sleep 2; }

  result="PASS"
  if ! wait_for "$DIR/server.log" 'Done \(' 300; then
    result="FAIL: server did not start"
  else
    for c in "tc help" "tc" "tc capacity" "tc capacity details" "tc perf" "tc monitor" "tc bench" \
      "tc bench list" "tc admin" "tc admin alerts"; do send "$c"; done
    send "tc bench start 2"
    wait_for "$DIR/server.log" 'Benchmark finished|Emergency stop|Stopped:' 300 || result="FAIL: benchmark timeout"
    sleep 3
    send "tc report"
    sleep 3
    send "tc bench list"
    send "tc"
  fi
  echo "stop" >>"$DIR/cmd.txt"
  wait_for "$DIR/server.log" 'Disabling TickCapacity' 60
  sleep 5
  pkill -f "tail -f cmd.txt" 2>/dev/null

  log="$DIR/server.log"
  if [ "$result" = "PASS" ]; then
    grep -q 'Enabling TickCapacity' "$log" || result="FAIL: plugin not enabled"
    grep -q 'Benchmark finished' "$log" || result="FAIL: benchmark did not finish"
    grep -q 'Report written' "$log" || result="FAIL: report not written"
    grep -q 'Players ' "$log" || result="FAIL: dashboard output missing"
    grep -q 'Causes' "$log" || result="FAIL: performance view missing"
    grep -q 'Real data' "$log" || result="FAIL: capacity view missing"
    # The smoke config omits entities-per-player on purpose: defaults must still spawn mobs.
    spawned="$(grep -oE 'Benchmark mobs spawned: [0-9]+' "$log" | tail -1 | grep -oE '[0-9]+$')"
    [ "${spawned:-0}" -gt 0 ] || result="FAIL: no benchmark mobs spawned"
    errors="$(grep -cE 'Exception|SEVERE|\[ERROR\]|Error occurred' "$log")"
    [ "$errors" = "0" ] || result="FAIL: $errors error lines in log"
  fi
  features="$(grep -o 'Server features: .*' "$log" | head -1)"
  echo "[$V] $result  (${features:-no feature line})"
  [ "$result" = "PASS" ] || overall=1
done
exit $overall
