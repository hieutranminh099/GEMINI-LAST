#!/usr/bin/env bash
set -euo pipefail
provider=${1:?Specify fixture, gemini or quality}
case "$provider" in
  gemini|quality) : "${GEMINI_BENCHMARK_API_KEY:?Set the repository Actions secret first}" ;;
  fixture) ;;
  *) exit 2 ;;
esac
: "${RUNNER_TEMP:?Run on the disposable CI emulator}"
package=com.gemini.ocr.bilingualtest
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
fixture=$(mktemp -d "$RUNNER_TEMP/bilingual.XXXXXX")
cd "$fixture"
trap 'adb shell pm clear "$package" >/dev/null || true; rm -rf "$fixture"' EXIT
umask 077
transfer() {
  adb shell -T run-as "$package" sh -c "\"umask 077; cat > files/$1\""
}
clear_fixture() {
  adb shell pm clear "$package" | tr -d '\r' | grep -Fxq Success
}
instrument() {
  local phase=$1 method=$2
  local test="com.gemini.ocr.video.BilingualVideoNativeTest#$method"
  adb shell am instrument -w -r -e bilingualProvider "$provider" -e bilingualPhase "$phase" -e class "$test" \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" > "result-$phase"
  local pattern='^(drive_authorization|model|translated|preview|subtitles|rendered|exported|service_retry|excerpt_[a-z0-9_]+)=[A-Za-z0-9._-]+$'
  if [[ -n ${VERIFICATION_EVIDENCE:-} ]]; then
    printf 'provider=%s phase=%s\n' "$provider" "$phase" >> "$VERIFICATION_EVIDENCE"
    adb exec-out run-as "$package" cat files/verification.txt 2>/dev/null | grep -E "$pattern" | tee -a "$VERIFICATION_EVIDENCE" || true
  else
    adb exec-out run-as "$package" cat files/verification.txt 2>/dev/null | grep -E "$pattern" || true
  fi
  if ! grep -Fq 'OK (1 test)' "result-$phase" || grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[234]' "result-$phase"; then
    grep -E 'AssertionError|VideoApiException|Illegal(State|Argument)Exception|TimeoutCancellationException|BilingualVideoNativeTest.kt:[0-9]+|FAILURES|Tests run|INSTRUMENTATION_(FAILED|CODE|RESULT): (shortMsg=Process (crashed|died)|-?[0-9]+)' "result-$phase" | head -12 || true
    adb shell dumpsys activity exit-info "$package" 2>/dev/null | grep -oE 'reason=[0-9]+ \([A-Z _]+\)|status=[0-9]+' | head -12 || true
    adb logcat -b crash -d 2>/dev/null | grep -oE 'Fatal signal [0-9]+ \(SIG[A-Z]+\)|java.lang.OutOfMemoryError|[A-Za-z]+Exception|#[0-9]+ pc [0-9a-f]+  /[^ ]+\.so' | tail -12 || true
    exit 1
  fi
  echo "Android integration passed: provider=$provider phase=$phase"
}
if [[ $provider == quality ]]; then
  clear_fixture
  adb shell run-as "$package" mkdir -p files
  printf '%s' "$GEMINI_BENCHMARK_API_KEY" | transfer credential
  instrument quality assessDialogueExcerptsWithGemini
  exit
fi
openssl rand -hex 32 > passphrase
# Original synthetic Mandarin speech and transcript, no external footage.
lines=('我妈是班主任。' '刘老师，您做了这么多菜。' '我帮您洗吧。')
for i in 0 1 2; do
  espeak-ng -v cmn -s 160 -w "voice-$i.wav" "${lines[$i]}"
  ffmpeg -nostdin -loglevel error -i "voice-$i.wav" -af apad -t 4 "slot-$i.wav"
done
printf "file 'slot-%s.wav'\n" 0 1 2 > audio.txt
cat > zh.srt <<'SRT'
1
00:00:00,000 --> 00:00:03,800
我妈是班主任。

2
00:00:04,000 --> 00:00:07,800
刘老师，您做了这么多菜。

3
00:00:08,000 --> 00:00:11,800
我帮您洗吧。
SRT
ffmpeg -nostdin -loglevel error -f lavfi -i color=c=navy:s=360x640:r=24:d=12 -f concat -safe 0 -i audio.txt \
  -vf "drawbox=x=22:y=470:w=316:h=131:color=white:t=fill,drawtext=text='中文字幕':font='Noto Sans CJK SC':fontcolor=black:fontsize=24:x=(w-tw)/2:y=480,drawtext=text='Original captions':fontcolor=black:fontsize=22:x=(w-tw)/2:y=568" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -t 12 dialogue.mp4
for phase in source recovery; do
  clear_fixture
  adb shell run-as "$package" mkdir -p files
  transfer dialogue.mp4 < dialogue.mp4
  transfer zh.srt < zh.srt
  transfer passphrase < passphrase
  if [[ $phase == source && $provider == gemini ]]; then
    printf '%s' "$GEMINI_BENCHMARK_API_KEY" | transfer credential
  elif [[ $phase == recovery ]]; then
    transfer sealed.json < sealed.json
    transfer expected-model < expected-model
  fi
  instrument "$phase" translateExportAndRecover
  if [[ $phase == source ]]; then
    adb exec-out run-as "$package" cat files/sealed.json > sealed.json
    adb exec-out run-as "$package" cat files/expected-model > expected-model
  fi
done
