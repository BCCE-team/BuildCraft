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

source "${script_dir}/ci-smoke-common.sh"

show_tail() {
  echo "--- ${client_log} (last 200 lines) ---"
  tail -n 200 "$client_log" 2>/dev/null || true
  if [[ -f "$latest_log" ]]; then
    echo "--- ${latest_log} (last 200 lines) ---"
    tail -n 200 "$latest_log" 2>/dev/null || true
  fi
}

cleanup() {
  terminate_smoke_process_group "${client_pid:-}"
}
trap cleanup EXIT

java_version="$(read_target_property java.version)"
java_home="$(select_java_home "$java_version")"
mkdir -p "$run_dir"
rm -f "$latest_log"
: > "$client_log"

# Minecraft 26.3 switched to SDL3/RenderPearl. Under Xvfb its OpenGL context
# requests a GLX visual that Mesa's virtual display does not expose. Provide
# Mesa Lavapipe's real Vulkan WSI/device instead of skipping the client smoke.
# Earlier targets retain their existing OpenGL setup and environment.
if [[ "$target" == "26.3-neoforge" ]]; then
  lavapipe_icd=""
  for candidate in /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /usr/share/vulkan/icd.d/lvp_icd.json; do
    if [[ -f "$candidate" ]]; then
      lavapipe_icd="$candidate"
      break
    fi
  done
  if [[ -z "$lavapipe_icd" ]]; then
    echo "Minecraft 26.3 needs the Mesa Lavapipe Vulkan ICD (mesa-vulkan-drivers)." >&2
    exit 2
  fi
  export VK_DRIVER_FILES="$lavapipe_icd"
  export VK_ICD_FILENAMES="$lavapipe_icd"
  export SDL_VIDEO_DRIVER=x11
  export LIBGL_ALWAYS_SOFTWARE=1
  echo "Minecraft 26.3: using Mesa Lavapipe Vulkan ICD $lavapipe_icd"

  if command -v vulkaninfo >/dev/null 2>&1; then
    vulkan_probe="$(mktemp)"
    if ! xvfb-run -a -s '-screen 0 1280x720x24' vulkaninfo > "$vulkan_probe" 2>&1; then
      echo "Lavapipe failed to initialize under Xvfb:" >&2
      tail -n 60 "$vulkan_probe" >&2
      rm -f "$vulkan_probe"
      exit 2
    fi
    for extension in VK_KHR_surface VK_KHR_xcb_surface VK_KHR_swapchain; do
      if ! grep -Fq "$extension" "$vulkan_probe"; then
        echo "Lavapipe is missing required Vulkan extension: $extension" >&2
        tail -n 60 "$vulkan_probe" >&2
        rm -f "$vulkan_probe"
        exit 2
      fi
    done
    rm -f "$vulkan_probe"
    echo "Lavapipe Vulkan display/swapchain extensions: OK"
  fi
fi

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
  # In 26.3 both rendering backends failing means startup is impossible. Fail
  # immediately rather than waiting the entire client startup timeout.
  if [[ "$target" == "26.3-neoforge" ]] \
      && grep -Eiq 'Failed to create backend OpenGL' "${logs[@]}" 2>/dev/null \
      && grep -Eiq 'Failed to create backend Vulkan' "${logs[@]}" 2>/dev/null; then
    echo "Minecraft 26.3 has no usable OpenGL or Vulkan rendering backend."
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
