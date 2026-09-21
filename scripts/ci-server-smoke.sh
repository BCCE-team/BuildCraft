#!/usr/bin/env bash
set -Eeuo pipefail

startup_timeout="${SERVER_STARTUP_TIMEOUT:-360}"
runtime_profile="${SERVER_RUNTIME_PROFILE:-base}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "${script_dir}/.." && pwd -P)"
target="${STONECUTTER_TARGET:-}"
generation="${BUILD_GENERATION:-}"

if [[ -n "$target" ]]; then
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
else
  generation="${generation:-legacy}"
  controller="${repo_root}/builds/${generation}/stonecutter.gradle.kts"
  if [[ ! -f "$controller" ]]; then
    echo "Unknown or unavailable build generation: ${generation}" >&2
    exit 2
  fi
  target="$(sed -nE 's/.*stonecutter active "([^"]+)".*/\1/p' "$controller" | head -n 1)"
fi

if [[ -z "$target" || -z "$generation" ]]; then
  echo "Unable to determine the BuildCraft target/build generation." >&2
  exit 2
fi

build_root="${repo_root}/builds/${generation}"
target_config="${repo_root}/build-config/targets.properties"
common_config="${repo_root}/build-config/common.properties"
if [[ ! -f "$target_config" || ! -x "${build_root}/gradlew" ]]; then
  echo "Incomplete ${generation} build root: ${build_root}" >&2
  exit 2
fi

if ! python3 "${repo_root}/scripts/source_layout.py" --list-targets --generation "$generation" | grep -Fxq "$target"; then
  echo "Target ${target} is not configured in the ${generation} build." >&2
  exit 2
fi

server_log="${SERVER_LOG_FILE:-${repo_root}/ci-server-${generation}-${target}-${runtime_profile}.log}"
server_pid=""
latest_log=""

read_property_file() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[^=]*=/, "")
      sub(/\r$/, "")
      print
      exit
    }
  ' "$file"
}

read_target_property() {
  local suffix="$1"
  local value
  value="$(read_property_file "$target_config" "target.${target}.${suffix}")"
  if [[ -z "$value" ]]; then
    value="$(read_property_file "$common_config" "common.${suffix}")"
  fi
  printf '%s\n' "$value"
}

select_java_home() {
  local major="$1"
  local var="JAVA_HOME_${major}_X64"
  local home="${!var:-}"

  if [[ -n "$home" && -x "$home/bin/java" ]]; then
    printf '%s\n' "$home"
    return 0
  fi

  if command -v java >/dev/null 2>&1; then
    local detected
    detected="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
    if [[ "$detected" == "$major" ]]; then
      local java_path
      java_path="$(readlink -f "$(command -v java)")"
      dirname "$(dirname "$java_path")"
      return 0
    fi
  fi

  echo "Java ${major} is required for ${target}, but ${var} is not available." >&2
  echo "Install every target JDK with actions/setup-java before running this script." >&2
  return 1
}

show_log_tail() {
  echo "--- ${server_log} (last 200 lines) ---"
  tail -n 200 "$server_log" 2>/dev/null || true

  if [[ -n "$latest_log" && -f "$latest_log" ]]; then
    echo "--- ${latest_log} (last 200 lines) ---"
    tail -n 200 "$latest_log" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" 2>/dev/null; then
    echo "Stopping CI server process group."
    kill -TERM -- "-$server_pid" 2>/dev/null || kill -TERM "$server_pid" 2>/dev/null || true

    for _ in $(seq 1 10); do
      if ! kill -0 "$server_pid" 2>/dev/null; then
        break
      fi
      sleep 1
    done

    if kill -0 "$server_pid" 2>/dev/null; then
      kill -KILL -- "-$server_pid" 2>/dev/null || kill -KILL "$server_pid" 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT

java_version="$(read_target_property java.version)"
mod_version="$(read_property_file "$common_config" "common.mod.version")"
expected_buildcraft_version="${mod_version}+${target//-/+}"
if [[ -z "$mod_version" ]]; then
  echo "Missing BuildCraft mod version metadata." >&2
  exit 2
fi
if [[ -z "$java_version" ]]; then
  echo "Missing Java version metadata for ${target}." >&2
  exit 2
fi
java_home="$(select_java_home "$java_version")"
java_bin="${java_home}/bin/java"

# The blocking CI smoke test uses the actual distributable jar on an actual
# installed dedicated server. Optional compatibility profiles use the userdev
# runtime because Gradle supplies their test-only dependencies.
if [[ "$runtime_profile" == "base" ]]; then
  minecraft_version="$(read_target_property deps.minecraft)"
  loader="${target##*-}"

  if [[ -z "$minecraft_version" ]]; then
    echo "Missing Minecraft metadata for ${target}." >&2
    exit 2
  fi

  case "$loader" in
    forge)
      loader_version="$(read_target_property deps.forge)"
      installer_url="https://maven.minecraftforge.net/net/minecraftforge/forge/${minecraft_version}-${loader_version}/forge-${minecraft_version}-${loader_version}-installer.jar"
      ;;
    neoforge)
      loader_version="$(read_target_property deps.neoforge)"
      installer_url="https://maven.neoforged.net/releases/net/neoforged/neoforge/${loader_version}/neoforge-${loader_version}-installer.jar"
      ;;
    *)
      echo "Installed-server smoke is not yet implemented for loader '${loader}' (${target})." >&2
      exit 2
      ;;
  esac

  if [[ -z "$loader_version" ]]; then
    echo "Missing loader version for ${target}." >&2
    exit 2
  fi

  jar_dir="${build_root}/versions/${target}/build/libs"
  if [[ ! -d "$jar_dir" ]]; then
    echo "Production jar directory does not exist: $jar_dir" >&2
    echo "Run the ${generation} buildAndCollect task before the server smoke test." >&2
    exit 2
  fi

  mapfile -t production_jars < <(
    find "$jar_dir" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort
  )
  if (( ${#production_jars[@]} != 1 )); then
    echo "Expected exactly one production jar in $jar_dir, found ${#production_jars[@]}." >&2
    printf '  %s\n' "${production_jars[@]}" >&2
    exit 2
  fi
  production_jar="${production_jars[0]}"

  server_dir="${repo_root}/run-server/${generation}/${target}"
  install_log="${repo_root}/ci-server-install-${generation}-${target}.log"
  latest_log="${server_dir}/logs/latest.log"

  rm -rf "$server_dir"
  mkdir -p "$server_dir"
  : > "$server_log"
  : > "$install_log"

  echo "Installing real ${loader} ${loader_version} dedicated server for Minecraft ${minecraft_version}."
  echo "Production mod under test: ${production_jar}"

  (
    cd "$server_dir"
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    curl --fail --location --silent --show-error --retry 3 --retry-delay 2 \
      --output loader-installer.jar "$installer_url"
    "$java_bin" -jar loader-installer.jar --installServer
    rm -f loader-installer.jar
  ) > "$install_log" 2>&1 || {
    echo "Dedicated server installation failed."
    echo "--- ${install_log} (last 200 lines) ---"
    tail -n 200 "$install_log" 2>/dev/null || true
    exit 1
  }

  if [[ ! -f "${server_dir}/run.sh" ]]; then
    echo "Loader installer completed without creating ${server_dir}/run.sh" >&2
    tail -n 200 "$install_log" 2>/dev/null || true
    exit 1
  fi

  mkdir -p "${server_dir}/mods"
  cp "$production_jar" "${server_dir}/mods/$(basename "$production_jar")"
  printf 'eula=true\n' > "${server_dir}/eula.txt"
  cat > "${server_dir}/server.properties" <<'PROPERTIES'
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-name=ci-world
motd=BuildCraft production jar CI smoke test
enable-command-block=false
spawn-protection=0
max-tick-time=-1
PROPERTIES
  cat > "${server_dir}/user_jvm_args.txt" <<'JVMARGS'
-Xms256M
-Xmx2G
-Dfile.encoding=UTF-8
JVMARGS

  echo "Starting installed dedicated server (generation: ${generation}, target: ${target}, timeout: ${startup_timeout}s)."
  (
    cd "$server_dir"
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    exec setsid bash run.sh nogui
  ) > "$server_log" 2>&1 &
  server_pid=$!
else
  run_dir="${repo_root}/run/${generation}/${target}"
  latest_log="${run_dir}/logs/latest.log"
  mkdir -p "$run_dir"
  printf 'eula=true\n' > "${run_dir}/eula.txt"
  cat > "${run_dir}/server.properties" <<'PROPERTIES'
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-name=ci-world
motd=BuildCraft compatibility CI server smoke test
enable-command-block=false
spawn-protection=0
max-tick-time=-1
PROPERTIES

  : > "$server_log"
  rm -f "$latest_log"
  echo "Starting compatibility userdev server (generation: ${generation}, target: ${target}, profile: ${runtime_profile}, timeout: ${startup_timeout}s)."
  (
    cd "$build_root"
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    exec setsid ./gradlew --no-daemon --console=plain --stacktrace \
      -Pci_runtime_profile="${runtime_profile}" ":${target}:runServer"
  ) > "$server_log" 2>&1 &
  server_pid=$!
fi

success_regex='Done \([0-9.,]+s\)! For help, type "help"|Done \([0-9.,]+s\)!'
fatal_regex='Failed to start the minecraft server|Exception in server tick loop|Loading errors encountered|Mod loading has failed|A fatal error has been detected|ResolutionException|UnsupportedClassVersionError'
deadline=$((SECONDS + startup_timeout))

while (( SECONDS < deadline )); do
  log_files=("$server_log")
  [[ -n "$latest_log" && -f "$latest_log" ]] && log_files+=("$latest_log")

  if grep -Eiq "$success_regex" "${log_files[@]}" 2>/dev/null; then
    if grep -Eq 'Starting BuildCraft[[:space:]]+(\$version|\$\{|.*\$\{)' "${log_files[@]}" 2>/dev/null; then
      echo "Dedicated server started with unresolved BuildCraft Java build metadata."
      show_log_tail
      exit 1
    fi
    if ! grep -Fq "Starting BuildCraft ${expected_buildcraft_version}" "${log_files[@]}" 2>/dev/null; then
      echo "Dedicated server did not report expected BuildCraft version ${expected_buildcraft_version}."
      show_log_tail
      exit 1
    fi
    echo "Dedicated server reached the ready state successfully with BuildCraft ${expected_buildcraft_version}."
    exit 0
  fi

  if grep -Eiq "$fatal_regex" "${log_files[@]}" 2>/dev/null; then
    echo "Dedicated server reported a fatal startup error."
    show_log_tail
    exit 1
  fi

  if ! kill -0 "$server_pid" 2>/dev/null; then
    set +e
    wait "$server_pid"
    status=$?
    set -e
    echo "Dedicated server process exited before becoming ready (status $status)."
    show_log_tail
    exit 1
  fi

  sleep 5
done

echo "Dedicated server did not reach the ready state within ${startup_timeout}s."
show_log_tail
exit 1
