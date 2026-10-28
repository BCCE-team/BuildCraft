#!/usr/bin/env bash
# Shared by the client and dedicated-server smoke runners.
# Script callers set target, target_config and common_config before sourcing.

read_property_file() {
  local file="$1" key="$2"
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit }' "$file"
}

read_target_property() {
  local suffix="$1" value
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
    local detected java_path
    detected="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
    if [[ "$detected" == "$major" ]]; then
      java_path="$(readlink -f "$(command -v java)")"
      dirname "$(dirname "$java_path")"
      return 0
    fi
  fi
  echo "Java ${major} is required for ${target}, but ${var} is not available." >&2
  return 1
}

terminate_smoke_process_group() {
  local pid="${1:-}"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do
      if ! kill -0 "$pid" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
}
