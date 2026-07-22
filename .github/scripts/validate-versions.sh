#!/usr/bin/env bash
set -euo pipefail

read_value() {
  sed -n "s/^$2=//p" "$1"
}

for file in release.txt dev.txt; do
  [[ -f "$file" ]] || { echo "Missing $file" >&2; exit 1; }
  [[ $(grep -c '^versionName=' "$file") -eq 1 ]] || { echo "$file needs one versionName" >&2; exit 1; }
  [[ $(grep -c '^versionCode=' "$file") -eq 1 ]] || { echo "$file needs one versionCode" >&2; exit 1; }
  [[ $(grep -Ec '^(versionName|versionCode)=' "$file") -eq $(grep -Ecve '^[[:space:]]*$' "$file") ]] || {
    echo "$file contains unsupported entries" >&2; exit 1;
  }
  code=$(read_value "$file" versionCode)
  [[ "$code" =~ ^[1-9][0-9]*$ ]] || { echo "Malformed versionCode in $file" >&2; exit 1; }
done

stable=$(read_value release.txt versionName)
dev=$(read_value dev.txt versionName)
[[ "$stable" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Malformed stable version' >&2; exit 1; }
[[ "$dev" =~ ^[0-9]+\.[0-9]+\.[0-9]+-dev\.[1-9][0-9]*$ ]] || { echo 'Malformed dev version' >&2; exit 1; }
[[ $(read_value release.txt versionCode) != $(read_value dev.txt versionCode) ]] || {
  echo 'versionCode must be unique across channels' >&2; exit 1;
}
