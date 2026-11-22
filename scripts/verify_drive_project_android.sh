#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 && $# -ne 4 ]]; then
  echo 'Usage: bash scripts/verify_drive_project_android.sh DEVICE_SERIAL APP_ID REPORT_DIR [PUBLISH_REPORT_JSON]' >&2
  exit 1
fi

serial=$1
app_id=$2
report_dir=$3
test_app_id=${DRIVE_TEST_APP_ID:-$app_id.test}
if [[ ! $app_id =~ ^com\.gemini\.ocr(\.[a-zA-Z][a-zA-Z0-9_]*)*$ || ! $test_app_id =~ ^com\.gemini\.ocr(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]]; then
  echo 'Specify the installed debug package and its instrumentation package.' >&2
  exit 1
fi

adb_device=(adb -s "$serial")
"${adb_device[@]}" shell pm path "$app_id" >/dev/null
"${adb_device[@]}" shell pm path "$test_app_id" >/dev/null
audit_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
remote_dir="files/stability-fixtures"
mkdir -p "$report_dir"
restore_args=()

if [[ $# -eq 4 ]]; then
  prior=$(python3 - "$4" <<'PY'
import json
import re
import sys
import uuid

with open(sys.argv[1], encoding='utf-8') as source:
    report = json.load(source)
if report['result'] != 'PASS' or report['mode'] != 'publish-and-restore':
    sys.exit('Provide a passing publication report.')
sha = report['sourceSha256']
if not re.fullmatch('[0-9a-f]{64}', sha) or report['restoredSha256'] != sha:
    sys.exit('Invalid fixture SHA-256 in publication report.')
print(uuid.UUID(report['fixtureProjectId']), sha)
PY
  )
  read -r project_id sha <<< "$prior"
  restore_args=(-e driveProjectId "$project_id")
else
  fixture_dir=$(mktemp -d)
  trap 'rm -r -- "$fixture_dir"' EXIT
  fixture="$fixture_dir/drive-project-fixture-$audit_id.mp4"
  ffmpeg -nostdin -loglevel error -f lavfi -i 'color=c=navy:s=360x640:r=24:d=4' \
    -an -c:v libx264 -pix_fmt yuv420p -threads 1 -movflags +faststart "$fixture"
  sha=$(python3 -c 'from hashlib import sha256; from pathlib import Path; import sys; print(sha256(Path(sys.argv[1]).read_bytes()).hexdigest())' "$fixture")
  "${adb_device[@]}" shell run-as "$app_id" mkdir -p "$remote_dir"
  "${adb_device[@]}" shell -T run-as "$app_id" sh -c \
    "\"umask 077; cat > $remote_dir/drive-project-fixture-$audit_id.mp4\"" < "$fixture"
fi

instrumentation_result=0
"${adb_device[@]}" shell am instrument -w \
  -e executeProjectDrive true -e driveAuditId "$audit_id" -e expectedSha256 "$sha" "${restore_args[@]}" \
  -e class com.gemini.ocr.video.BilingualDriveProjectNativeTest \
  "$test_app_id/androidx.test.runner.AndroidJUnitRunner" || instrumentation_result=$?

report_name="drive-project-audit-$audit_id.json"
report="$report_dir/$report_name"
"${adb_device[@]}" exec-out run-as "$app_id" cat "$remote_dir/$report_name" > "$report"
python3 - "$report" "$instrumentation_result" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as source:
    report = json.load(source)
result = report['result']
print(f"{result} ({report['phase']}) · {sys.argv[1]}")
if result == 'BLOCKED':
    sys.exit(2)
sys.exit(0 if result == 'PASS' and sys.argv[2] == '0' else 1)
PY
