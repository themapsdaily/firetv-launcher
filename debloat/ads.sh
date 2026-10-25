#!/usr/bin/env bash
# Adds debloat/ads.hosts to personalDNSfilter's own override file (additionalHosts.txt) on the TV,
# between marker lines, so it stays apart from dumbtv's pushed blocklist and comes out cleanly.
#   bash debloat/ads.sh            apply ads.hosts (replaces an earlier block)
#   bash debloat/ads.sh --undo     remove the block
#   bash debloat/ads.sh --check    show whether each host answers as blocked right now
# The filter reads the file at start, so a change applies after a reboot (dumbtv: never force-stop
# the filter, it can drop out of the DNS path). Needs ADB and ADB_SERIAL, as home/install.sh.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "${BASH_SOURCE[0]}")"

FILE=/sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/additionalHosts.txt
BEGIN='# >>> fire-tv debloat/ads.hosts'
END='# <<< fire-tv debloat/ads.hosts'
ADB=${ADB:-adb}
adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
hosts() { sed 's/#.*//' ads.hosts | awk 'NF { print $1 }'; }

if [[ ${1:-} == --check ]]; then
  while read -r h; do
    [[ $h == !* ]] && continue
    line=$(adb_ shell "ping -c1 -W2 $h" </dev/null 2>&1 | head -1 | tr -d '\r')
    case $line in
      *0.0.0.0*|*127.0.0.1*) printf '  %-36s blocked\n' "$h" ;;
      *) printf '  %-36s not blocked\n' "$h" ;;
    esac
  done < <(hosts)
  exit 0
fi

WORK=$(mktemp -d)
# adb.exe on Git Bash needs a Windows path, since path conversion is off above.
command -v cygpath >/dev/null && WORK=$(cygpath -m "$WORK")
trap 'rm -rf "$WORK"' EXIT
adb_ pull "$FILE" "$WORK/now.txt" >/dev/null || { echo "error: could not read $FILE (is personalDNSfilter set up?)" >&2; exit 1; }
cp "$WORK/now.txt" "$WORK/before.txt"
# Everything outside our block stays as it was.
awk -v b="$BEGIN" -v e="$END" '$0 == b { skip = 1; next } $0 == e { skip = 0; next } !skip' "$WORK/now.txt" \
  | tr -d '\r' > "$WORK/new.txt"
if [[ ${1:-} != --undo ]]; then
  { echo "$BEGIN"; hosts; echo "$END"; } >> "$WORK/new.txt"
fi
if cmp -s "$WORK/new.txt" <(tr -d '\r' < "$WORK/before.txt"); then
  echo "no change"
  exit 0
fi
adb_ push "$WORK/new.txt" "$FILE" >/dev/null
if [[ ${1:-} == --undo ]]; then echo "removed the ads.hosts block"; else echo "added $(hosts | wc -l) hosts"; fi
echo "reboot the TV to apply (adb reboot), then: bash debloat/ads.sh --check"
