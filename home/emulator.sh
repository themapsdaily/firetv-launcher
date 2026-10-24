#!/usr/bin/env bash
# Local test run: an Android 9 TV emulator at 1080p (the stick's Android version and screen), then
# install.sh against it, so changes are checked on the PC before they go to the Fire TV.
#   ./emulator.sh            create the emulator once, start it if needed, build and install Home
#   ./emulator.sh --stop     shut the emulator down
# Needs the SDK's emulator and system-images;android-28;android-tv;x86 (sdkmanager), Windows
# Hypervisor Platform turned on, JAVA_HOME, and ADB (path to adb).
# Fire OS parts have nothing to act on here: there is no Amazon home for Guard to replace, and no FanCode.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK=${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}
AVD=firetv
SERIAL=emulator-5554
ADB=${ADB:-adb}
adb_() { "$ADB" -s "$SERIAL" "$@"; }

if [[ ${1:-} == --stop ]]; then
  adb_ emu kill >/dev/null 2>&1 || true
  echo "emulator stopped"
  exit 0
fi

if ! "$SDK/emulator/emulator.exe" -list-avds | tr -d '\r' | grep -qx "$AVD"; then
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd -n "$AVD" \
    -k "system-images;android-28;android-tv;x86" -d tv_1080p >/dev/null
  # The stick has about 1 GB; give the emulator a little more so it boots quickly.
  cfg="$USERPROFILE/.android/avd/$AVD.avd/config.ini"
  sed -i 's/^hw.ramSize=.*/hw.ramSize=1536/' "$cfg"
  grep -q '^hw.ramSize=' "$cfg" || echo 'hw.ramSize=1536' >> "$cfg"
  echo "created emulator $AVD (Android 9 TV, 1080p)"
fi

if ! "$ADB" devices | grep -q "^$SERIAL"; then
  "$SDK/emulator/emulator.exe" -avd "$AVD" -no-snapshot-save -no-boot-anim -gpu auto >/dev/null 2>&1 &
  echo "starting emulator..."
  "$ADB" -s "$SERIAL" wait-for-device
  until [[ $(adb_ shell getprop sys.boot_completed | tr -d '\r') == 1 ]]; do sleep 2; done
  echo "emulator booted"
fi

# The stick runs on IST. -timezone is ignored when the emulator boots from a snapshot, so set it
# through the alarm service (setTimeZone), which the shell may call.
adb_ shell service call alarm 3 s16 Asia/Kolkata >/dev/null

ADB_SERIAL=$SERIAL bash install.sh
# Guard only replaces Amazon's home. Here Google's TV launcher would take HOME back, so switch it
# off: this is a throwaway test device, never the Fire TV.
adb_ shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null 2>&1 || true
adb_ shell am start -n com.siddh.home/.Home >/dev/null
