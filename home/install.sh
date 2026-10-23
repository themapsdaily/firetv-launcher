#!/usr/bin/env bash
# Installs Home on the TV and gives it the HOME button. Guard handling follows dumbtv's
# launcher/install.sh (MIT, https://github.com/humblemuzzu/dumbtv).
#   ./install.sh           build, install, take over HOME, enable Continue watching, set the screensaver
#   ./install.sh --undo    hand HOME back to dumbtv home (still installed), restore the old screensaver
# Reads API_KEY (TMDB v3) from ../.env and passes it to the TV; it is never built into the APK.
# Needs ADB (path to adb) and ADB_SERIAL (e.g. 192.168.1.5:5555) in the environment.
set -euo pipefail
# Git Bash on Windows rewrites /sdcard/... arguments into Windows paths; keep them as they are.
export MSYS_NO_PATHCONV=1
# ...which also stops JAVA_HOME being converted for Gradle, so it must be a Windows path.
[[ ${JAVA_HOME:-} == /[a-z]/* ]] && JAVA_HOME=$(cygpath -m "$JAVA_HOME")
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=com.siddh.home
GUARD=$PKG/$PKG.Guard
WATCHER=$PKG/$PKG.Watcher
DREAM=$PKG/$PKG.Dream
DUMB_GUARD=tv.dumbtv.home/tv.dumbtv.home.Guard
BEFORE=/data/local/tmp/home-screensaver-before.txt
CITY=${CITY:-Pune}
ADB=${ADB:-adb}

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null | tr -d '\r'; }

# Puts $1 (or nothing) in place of any launcher's HOME guard, keeping every other service.
set_guard() {
  local cur
  cur=$(sh_ settings get secure enabled_accessibility_services)
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF -e "$GUARD" -e "$DUMB_GUARD" -e '' || true; } | paste -sd: -)
  if [[ -n ${1:-} ]]; then cur=${cur:+$cur:}$1; fi
  # Clearing first makes the accessibility manager rebind a service whose process died.
  sh_ settings put secure enabled_accessibility_services '""' >/dev/null
  sleep 1
  if [[ -n $cur ]]; then
    sh_ settings put secure enabled_accessibility_services "$cur" >/dev/null
    sh_ settings put secure accessibility_enabled 1 >/dev/null
  else
    sh_ settings put secure accessibility_enabled 0 >/dev/null
  fi
}

if [[ ${1:-} == --undo ]]; then
  sh_ cmd notification disallow_listener "$WATCHER" >/dev/null 2>&1 || true
  sh_ "if [ -f $BEFORE ]; then { read c; read e; } < $BEFORE; settings put secure screensaver_components \"\$c\"; settings put secure screensaver_enabled \"\$e\"; rm -f $BEFORE; fi"
  echo "screensaver: $(sh_ settings get secure screensaver_components)"
  if sh_ pm path tv.dumbtv.home | grep -q '^package:'; then
    set_guard "$DUMB_GUARD"
    sh_ am start -n tv.dumbtv.home/.Home >/dev/null
    echo "HOME handed back to dumbtv home ($PKG stays installed: adb uninstall $PKG)"
  else
    set_guard ""
    sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
    echo "HOME handed back to Amazon's launcher"
  fi
  exit 0
fi

./gradlew.bat assembleRelease --console=plain -q
APK=app/build/outputs/apk/release/app-release.apk
out=$(adb_ install -r "$APK" 2>&1 | grep -m1 -E 'Success|Failure|failed' || true)
[[ $out == *Success* ]] || { echo "error: install failed: $out" >&2; exit 1; }
echo "installed $PKG ($(du -k "$APK" | cut -f1) KB)"

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
sh_ cmd package set-home-activity $PKG/.Home >/dev/null 2>&1 || true
# Alarms normally fire no sooner than 5 s out; the HOME switch needs them immediate.
fut=$(sh_ settings get global alarm_manager_constants)
if [[ $fut != *min_futurity=0* ]]; then
  [[ $fut == null ]] && fut=
  sh_ settings put global alarm_manager_constants "${fut:+$fut,}min_futurity=0" >/dev/null
fi
set_guard "$GUARD"
sh_ cmd notification allow_listener "$WATCHER" >/dev/null || echo "note: could not enable Continue watching"
# Owner's pictures: art/<package>/* -> the app's shelf folder on the TV, mirrored.
SHELF=/sdcard/Android/data/$PKG/files/shelf
for d in art/*/; do
  [[ -d $d ]] || continue
  p=$(basename "$d")
  sh_ "rm -rf $SHELF/$p; mkdir -p $SHELF/$p"
  for f in "$d"*; do [[ -f $f ]] && adb_ push "$f" "$SHELF/$p/$(basename "$f")" >/dev/null; done
  echo "art: $p ($(ls "$d" | wc -l) pictures)"
done
# Screensaver: remember Amazon's first, so --undo can put it back.
sh_ "[ -f $BEFORE ] || { settings get secure screensaver_components; settings get secure screensaver_enabled; } > $BEFORE"
sh_ settings put secure screensaver_components "$DREAM" >/dev/null
sh_ settings put secure screensaver_enabled 1 >/dev/null
echo "screensaver: $(sh_ settings get secure screensaver_components)"
sleep 2
# The service is listed by its label, "Home button".
if sh_ dumpsys accessibility | grep -q "label=Home button"; then echo "home guard: bound"; else echo "warning: home guard not bound yet" >&2; fi
KEY=$(grep -m1 '^API_KEY=' ../.env 2>/dev/null | cut -d= -f2- | tr -d '\r' || true)
sh_ am start -W -n $PKG/.Home --ez refresh true --es city "$CITY" ${KEY:+--es tmdb "$KEY"} >/dev/null
[[ -n $KEY ]] && echo "tmdb key: passed" || echo "note: no API_KEY in ../.env; trending pictures stay off"
printf 'front window: %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"
