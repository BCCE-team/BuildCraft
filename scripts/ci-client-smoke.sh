#!/usr/bin/env bash
set -Eeuo pipefail

startup_timeout="${CLIENT_STARTUP_TIMEOUT:-480}"
stability_seconds="${CLIENT_STABILITY_SECONDS:-20}"
runtime_profile="${CLIENT_RUNTIME_PROFILE:-jei}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/.." && pwd -P)"
target="${STONECUTTER_TARGET:-}"
generation="${BUILD_GENERATION:-}"

if [[ -z "$target" ]]; then
  echo "STONECUTTER_TARGET is required for client smoke tests." >&2
  exit 2
fi

if [[ -z "$generation" ]]; then
  generation="$({
    REPO_ROOT="$repo_root" TARGET_ID="$target" python3 - <<'PY'
import os
import sys
from pathlib import Path
root = Path(os.environ["REPO_ROOT"])
sys.path.insert(0, str(root / "scripts"))
from source_layout import load_properties, target_layout
print(target_layout(os.environ["TARGET_ID"], load_properties()).generation)
PY
  })"
fi

build_root="${repo_root}/builds/${generation}"
target_config="${repo_root}/build-config/targets.properties"
common_config="${repo_root}/build-config/common.properties"
run_dir="${repo_root}/run/${generation}/${target}"
client_log="${CLIENT_LOG_FILE:-${repo_root}/ci-client-${generation}-${target}-${runtime_profile}.log}"
latest_log="${run_dir}/logs/latest.log"
client_pid=""

if [[ ! -x "${build_root}/gradlew" || ! -f "$target_config" ]]; then
  echo "Incomplete ${generation} build root: ${build_root}" >&2
  exit 2
fi
if ! command -v xvfb-run >/dev/null 2>&1; then
  echo "xvfb-run is required for the client smoke test." >&2
  exit 2
fi

read_property_file() {
  local file="$1" key="$2"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$file"
}
read_target_property() {
  local suffix="$1" value
  value="$(read_property_file "$target_config" "target.${target}.${suffix}")"
  if [[ -z "$value" ]]; then value="$(read_property_file "$common_config" "common.${suffix}")"; fi
  printf '%s\n' "$value"
}
select_java_home() {
  local major="$1"
  local var="JAVA_HOME_${major}_X64"
  local home="${!var:-}"
  if [[ -n "$home" && -x "$home/bin/java" ]]; then printf '%s\n' "$home"; return 0; fi
  if command -v java >/dev/null 2>&1; then
    local detected java_path
    detected="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
    if [[ "$detected" == "$major" ]]; then
      java_path="$(readlink -f "$(command -v java)")"
      dirname "$(dirname "$java_path")"
      return 0
    fi
  fi
  echo "Java ${major} is required for ${target}, but JAVA_HOME_${major}_X64 is unavailable." >&2
  return 1
}

show_tail() {
  echo "--- ${client_log} (last 200 lines) ---"
  tail -n 200 "$client_log" 2>/dev/null || true
  if [[ -f "$latest_log" ]]; then
    echo "--- ${latest_log} (last 200 lines) ---"
    tail -n 200 "$latest_log" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ -n "$client_pid" ]] && kill -0 "$client_pid" 2>/dev/null; then
    kill -TERM -- "-$client_pid" 2>/dev/null || kill -TERM "$client_pid" 2>/dev/null || true
    for _ in $(seq 1 10); do
      if ! kill -0 "$client_pid" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$client_pid" 2>/dev/null; then
      kill -KILL -- "-$client_pid" 2>/dev/null || kill -KILL "$client_pid" 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT

java_version="$(read_target_property java.version)"
java_home="$(select_java_home "$java_version")"
mkdir -p "$run_dir"
rm -f "$latest_log"
: > "$client_log"

# The JEI-only profile keeps the client small while still exercising BuildCraft's
# recipe-viewer registration in addition to model bake, texture stitching and GUI classloading.
echo "Starting client smoke ${generation}/${target} (profile=${runtime_profile}, timeout=${startup_timeout}s)."
(
  cd "$build_root"
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
  exec setsid xvfb-run -a -s '-screen 0 1280x720x24' \
    ./gradlew --no-daemon --console=plain --stacktrace \
    -Pci_runtime_profile="${runtime_profile}" ":${target}:runClient"
) > "$client_log" 2>&1 &
client_pid=$!

ready_regex='Created: .*minecraft:textures/atlas/blocks\.png-atlas|minecraft:textures/atlas/blocks\.png-atlas'
fatal_regex='Mod loading has failed|Loading errors encountered|Exception caught during firing event|A fatal error has been detected|UnsupportedClassVersionError|NoClassDefFoundError|ExceptionInInitializerError|ReportedException: Rendering|Caught exception from BuildCraft|Crash report saved to'
resource_regex='(Missing model for variant|Failed to load model|Unable to load model|Using missing texture, unable to load).*(buildcraft)|buildcraft.*(Missing model for variant|Failed to load model|Unable to load model|Using missing texture, unable to load)|Invalid path in pack: buildcraft'
deadline=$((SECONDS + startup_timeout))
ready_at=0
resource_error_seen=0

while (( SECONDS < deadline )); do
  logs=("$client_log")
  [[ -f "$latest_log" ]] && logs+=("$latest_log")

  if grep -Eiq "$fatal_regex" "${logs[@]}" 2>/dev/null; then
    echo "Client reported a fatal startup/render error."
    show_tail
    exit 1
  fi
  if grep -Eiq "$resource_regex" "${logs[@]}" 2>/dev/null; then
    if (( resource_error_seen == 0 )); then
      echo "Client reported a BuildCraft resource/model error; collecting the rest of the reload before failing."
    fi
    resource_error_seen=1
  fi

  if (( ready_at == 0 )) && grep -Eiq "$ready_regex" "${logs[@]}" 2>/dev/null; then
    ready_at=$SECONDS
    echo "Client completed block-atlas creation; observing ${stability_seconds}s for late client errors."
  fi

  if (( ready_at > 0 && SECONDS - ready_at >= stability_seconds )); then
    if (( resource_error_seen != 0 )); then
      echo "Client completed model/atlas startup but BuildCraft resource/model errors were detected."
      show_tail
      exit 1
    fi
    echo "Client smoke reached model/atlas-ready state and remained stable."
    exit 0
  fi

  if ! kill -0 "$client_pid" 2>/dev/null; then
    set +e
    wait "$client_pid"
    status=$?
    set -e
    echo "Client process exited before smoke completion (status $status)."
    show_tail
    exit 1
  fi
  sleep 3
done

echo "Client did not finish model/atlas startup within ${startup_timeout}s."
show_tail
exit 1
