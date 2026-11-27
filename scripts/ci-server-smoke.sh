#!/usr/bin/env bash
set -Eeuo pipefail

startup_timeout="${SERVER_STARTUP_TIMEOUT:-360}"
runtime_profile="${SERVER_RUNTIME_PROFILE:-base}"
api_test_addon="${BCCE_API_TEST_ADDON:-}"
if [[ -n "$api_test_addon" && "$runtime_profile" != base ]]; then
  echo "API test addon requires the installed production server profile." >&2
  exit 2
fi
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
mkdir -p "$(dirname "$server_log")"
server_pid=""
latest_log=""

source "${script_dir}/ci-smoke-common.sh"

show_log_tail() {
  echo "--- ${server_log} (last 200 lines) ---"
  tail -n 200 "$server_log" 2>/dev/null || true

  if [[ -n "$latest_log" && -f "$latest_log" ]]; then
    echo "--- ${latest_log} (last 200 lines) ---"
    tail -n 200 "$latest_log" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ -n "${server_pid:-}" ]] && kill -0 "$server_pid" 2>/dev/null; then
    echo "Stopping CI server process group."
  fi
  terminate_smoke_process_group "${server_pid:-}"
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
    fabric)
      loader_version="$(read_target_property deps.fabric_loader)"
      installer_version="$(read_target_property deps.fabric_installer)"
      if [[ -z "$installer_version" ]]; then
        echo "Fabric installer version is not pinned for ${target}" >&2
        exit 2
      fi
      installer_url="https://maven.fabricmc.net/net/fabricmc/fabric-installer/${installer_version}/fabric-installer-${installer_version}.jar"
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

  archive_name="$(read_property_file "$common_config" "common.mod.archive_name")"
  jar_dir="${repo_root}/build/${minecraft_version}"
  production_jar="${jar_dir}/${archive_name}-${expected_buildcraft_version}.jar"
  if [[ ! -f "$production_jar" ]]; then
    echo "Production jar does not exist: $production_jar" >&2
    echo "Run the ${generation} buildAndCollect task before the server smoke test." >&2
    exit 2
  fi

  if [[ -n "$api_test_addon" ]]; then
    python3 "${repo_root}/scripts/build-api-test-addon.py" check --target "$target" \
      --bcce-jar "$production_jar" --output "$api_test_addon"
  fi

  server_dir="${repo_root}/run-server/${generation}/${target}"
  install_log="${SERVER_INSTALL_LOG_FILE:-${repo_root}/ci-server-install-${generation}-${target}.log}"
  mkdir -p "$(dirname "$install_log")"
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
    if [[ "$loader" == fabric ]]; then
      "$java_bin" -jar loader-installer.jar server -mcversion "$minecraft_version" -loader "$loader_version" -downloadMinecraft
    else
      "$java_bin" -jar loader-installer.jar --installServer
    fi
    rm -f loader-installer.jar
  ) > "$install_log" 2>&1 || {
    echo "Dedicated server installation failed."
    echo "--- ${install_log} (last 200 lines) ---"
    tail -n 200 "$install_log" 2>/dev/null || true
    exit 1
  }

  expected_server_launcher="${server_dir}/run.sh"
  if [[ "$loader" == fabric ]]; then expected_server_launcher="${server_dir}/fabric-server-launch.jar"; fi
  if [[ ! -f "$expected_server_launcher" ]]; then
    echo "Loader installer completed without creating ${expected_server_launcher}" >&2
    tail -n 200 "$install_log" 2>/dev/null || true
    exit 1
  fi

  mkdir -p "${server_dir}/mods"
  cp "$production_jar" "${server_dir}/mods/$(basename "$production_jar")"
  if [[ -n "$api_test_addon" ]]; then
    cp "$api_test_addon" "${server_dir}/mods/bcce-api-test.jar"
    # A clean fixture exercises both global and world-specific legacy migrations.
    # Java Properties requires an escaped colon in a namespaced key.
    mkdir -p "${server_dir}/config/buildcraft" "${server_dir}/ci-world/buildcraft"
    printf '%s\n' 'bcceapitest\:old_capacity=41' > "${server_dir}/config/buildcraft/server-defaults.properties"
    printf '%s\n' 'bcceapitest\:old_capacity=73' > "${server_dir}/ci-world/buildcraft/configuration.properties"
    export BCCE_API_TEST_EXPECTED_CAPACITY=73
  fi
  if [[ "$loader" == fabric ]]; then
    fabric_dependency_dir="${repo_root}/build/server-deps/${target}"
    # Fail closed if the production server would be missing mandatory Fabric API
    # or TeamReborn Energy, regardless of whether the userdev run succeeded.
    shopt -s nullglob
    fabric_dependency_jars=("${fabric_dependency_dir}"/*.jar)
    shopt -u nullglob
    if [[ ${#fabric_dependency_jars[@]} -lt 2 ]]; then
      echo "Missing mandatory Fabric server mods in ${fabric_dependency_dir}; run collectFabricServerMods" >&2
      exit 2
    fi
    cp "${fabric_dependency_jars[@]}" "${server_dir}/mods/"
  fi
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
    if [[ "$loader" == fabric ]]; then
      exec setsid "$java_bin" -Xms256M -Xmx2G -Dfile.encoding=UTF-8 -jar fabric-server-launch.jar nogui
    else
      exec setsid bash run.sh nogui
    fi
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

  if [[ -n "$api_test_addon" ]] && grep -Eq 'BCCE_API_TEST FAIL v1[[:space:]]*$' "${log_files[@]}" 2>/dev/null; then
    echo "Independent API test addon reported failure."
    show_log_tail
    exit 1
  fi

  if grep -Eiq "$fatal_regex" "${log_files[@]}" 2>/dev/null; then
    echo "Dedicated server reported a fatal startup error."
    show_log_tail
    exit 1
  fi

  # ServerStartedEvent can be emitted after Minecraft logs Done. Do not pass
  # until the independent addon has registered and completed its assertions.
  if grep -Eiq "$success_regex" "${log_files[@]}" 2>/dev/null &&
     { [[ -z "$api_test_addon" ]] || api_test_addon_passed "${log_files[@]}"; }; then
    if grep -Eq 'Starting BuildCraft[[:space:]]+(\$version|\$\{|.*\$\{)' "${log_files[@]}" 2>/dev/null; then
      echo "Dedicated server started with unresolved BuildCraft Java build metadata."
      show_log_tail
      exit 1
    fi
    # Older loaders log the base version; Fabric logs the target-qualified
    # version. Require one of those two exact tokens, never a partial match.
    if ! buildcraft_startup_banner_matches "$mod_version" "$expected_buildcraft_version" "${log_files[@]}"; then
      echo "Dedicated server did not report BuildCraft startup version ${mod_version} or ${expected_buildcraft_version}."
      show_log_tail
      exit 1
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
      echo "Server exited before the smoke gate completed." >&2
      show_log_tail
      exit 1
    fi
    if [[ -n "$api_test_addon" ]]; then
      echo "Independent API test addon passed on the installed production server."
    fi
    echo "Dedicated server reached the ready state successfully with BuildCraft ${expected_buildcraft_version}."
    exit 0
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

echo "Dedicated server or independent API test addon did not reach the required ready state within ${startup_timeout}s."
show_log_tail
exit 1
