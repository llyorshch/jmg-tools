#!/usr/bin/env bash
# Builds a native executable for every tzu command into ./build (needs GraalVM 22+ with native-image).
#   ./build-native.sh            all commands
#   ./build-native.sh tz-map     just one
set -euo pipefail
cd "$(dirname "$0")"
script_for() {
  case "$1" in
    tz-now) echo TzNow.java ;;
    tz-meet) echo TzMeet.java ;;
    tz-holidays) echo TzHolidays.java ;;
    tz-find) echo TzFind.java ;;
    tz-map) echo TzMap.java ;;
    *) echo "unknown command: $1" >&2; exit 2 ;;
  esac
}
if [ $# -eq 0 ]; then set -- tz-now tz-meet tz-holidays tz-find tz-map; fi
mkdir -p build
for t in "$@"; do
  echo "==> $t"
  jbang export native --force -O "build/$t" "$(script_for "$t")"
done
ls -lh build
