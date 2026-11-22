#!/usr/bin/env bash
set -euo pipefail

scenario=${1:?Specify edits, interruption, or long}
case "$scenario" in
  edits|interruption|long) ;;
  *) exit 2 ;;
esac
: "${RUNNER_TEMP:?Run on a disposable Android emulator}"
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]

package=com.gemini.ocr.codexbench
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
scratch=$(mktemp -d "$RUNNER_TEMP/video-stability.XXXXXX")
trap 'adb shell pm clear "$package" >/dev/null || true; rm -rf "$scratch"' EXIT
adb shell pm clear "$package" | grep -Fq Success
adb shell run-as "$package" mkdir -p files

run_test() {
  local test_class=$1 limit=$2
  shift 2
  local report="$scratch/instrumentation.log"
  if ! timeout "$limit" adb shell am instrument -w -r -e class "$test_class" "$@" "$runner" > "$report" ||
     ! grep -Fq 'OK (1 test)' "$report" || grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[234]' "$report"; then
    tail -60 "$report"
    return 1
  fi
}

if [[ $scenario != interruption ]]; then
  fixture="$root/scripts/fixtures/long-video-ocr-benchmark.mp4"
  printf '%s  %s\n' bfbc8fe36aad8e052e15cd875ddf043c99a029e6ef60c57a443e3513834306b4 "$fixture" | sha256sum --check
  adb shell -T run-as "$package" sh -c '"cat > files/long-video-ocr-benchmark.mp4"' < "$fixture"
fi

case "$scenario" in
  edits)
    run_test com.gemini.ocr.video.BilingualVideoEditsNativeTest 60s -e executeBilingualEdits true
    echo 'Bilingual edits, cue joining, and reopening preserved both subtitle tracks.'
    ;;
  interruption)
    test_class=com.gemini.ocr.video.StabilityProcessDeathNativeTest
    run_test "$test_class" 90s -e executeStabilityProcess true -e stabilityProcessPhase start
    adb shell am force-stop "$package"
    run_test "$test_class" 240s -e executeStabilityProcess true -e stabilityProcessPhase resume
    echo 'An actual process restart preserved the approved checkpoint and completed MP4 export.'
    ;;
  long)
    run_test com.gemini.ocr.batch.LongVideoBenchmarkTest 18m -e longVideoBenchmark true
    adb exec-out run-as "$package" cat files/long-video-result.json
    printf '\n'
    ;;
esac
