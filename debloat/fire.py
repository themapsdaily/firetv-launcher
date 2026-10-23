"""Reversible Fire TV debloat, one batch at a time, over cleanyourtv's pure-Python ADB client.

Run from D:\\Projects\\fire-tv with TV_HOST set (or in .env):
    venv-python debloat/fire.py check                    home activity, disabled list, free memory
    venv-python debloat/fire.py disable debloat/batch1-stubs.pkgs
    venv-python debloat/fire.py enable  debloat/batch1-stubs.pkgs   undo one batch
    venv-python debloat/fire.py enable  all              undo every batch
    venv-python debloat/fire.py settings debloat/batch6-privacy.settings
    venv-python debloat/fire.py settings debloat/restore.settings   undo the settings

Packages are only ever `pm disable-user --user 0`, never uninstalled. Anything on dumbtv's
never-disable list is refused. Every result is appended to debloat/log.tsv.
"""
import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LOG = os.path.join(HERE, "log.tsv")
NEVER = os.path.join(ROOT, "dumbtv", "data", "fireos", "never-disable.txt")

# Load TV_HOST from .env before tv.py reads it at import time.
env = os.path.join(ROOT, ".env")
if os.path.exists(env):
    for line in open(env):
        k, _, v = line.strip().partition("=")
        if k and v:
            os.environ.setdefault(k, v)

sys.path.insert(0, os.path.join(ROOT, "cleanyourtv", "tools"))
import tv  # noqa: E402

# On top of dumbtv's list: the categories the project rules say never to touch.
EXTRA_NEVER = {"com.android.vending", "com.google.android.gms", "com.amazon.tv.inputpreference.service",
               "com.android.inputmethod.latin", "com.amazon.tv.ime"}


def read_list(path):
    out = []
    for line in open(path):
        word = line.split("#", 1)[0].strip()
        if word:
            out.append(word.split()[0])
    return out


def never_disable():
    return set(read_list(NEVER)) | EXTRA_NEVER


def log(pkg, action, result):
    with open(LOG, "a") as f:
        f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')}\t{action}\t{pkg}\t{result}\n")


def pkgs_from(arg):
    if arg == "all":
        seen = []
        for path in sorted(glob.glob(os.path.join(HERE, "*.pkgs"))):
            seen += [p for p in read_list(path) if p not in seen]
        return seen
    return read_list(arg)


def first_line(s):
    return (s or "").strip().splitlines()[0] if (s or "").strip() else "(no output)"


def check(dev):
    act = dev.shell("dumpsys activity activities | grep -m1 mResumedActivity")
    print("resumed  :", act.strip().split(" u0 ")[-1].split("}")[0] if act.strip() else "(none)")
    home = dev.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                     "-c android.intent.category.HOME | tail -1")
    print("home     :", home.strip())
    mem = dev.shell("grep MemAvailable /proc/meminfo")
    print("memory   :", mem.strip())
    dis = dev.shell("pm list packages -d").split()
    print(f"disabled : {len(dis)}")
    for p in sorted(dis):
        print("   ", p.replace("package:", ""))


def disable(dev, pkgs):
    blocked = never_disable()
    installed = {p.replace("package:", "") for p in dev.shell("pm list packages").split()}
    for p in pkgs:
        if p in blocked:
            print(f"  {p:48} REFUSED (never-disable)"); log(p, "disable", "refused"); continue
        if p not in installed:
            print(f"  {p:48} absent"); log(p, "disable", "absent"); continue
        out = first_line(dev.shell(f"pm disable-user --user 0 {p} 2>&1"))
        if "disabled-user" in out:
            result = "disabled"
        elif "protected" in out:
            result = "PROTECTED"
        elif "Shell cannot change component state" in out:
            result = "already disabled by the system"
        else:
            result = "SUSPECT: " + out
        print(f"  {p:48} {result}"); log(p, "disable", result)


def enable(dev, pkgs):
    for p in pkgs:
        out = first_line(dev.shell(f"pm enable --user 0 {p} 2>&1"))
        print(f"  {p:48} {out}"); log(p, "enable", out)


def settings(dev, path):
    for line in open(path):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        ns, key, value = line.split()
        dev.shell(f"settings put {ns} '{key}' '{value}'")
        now = dev.shell(f"settings get {ns} '{key}'").strip()
        print(f"  {ns:7} {key:48} {now}"); log(key, f"settings {ns}", now)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("check", "disable", "enable", "settings"):
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd != "check" and len(sys.argv) != 3:
        sys.exit(__doc__)
    dev = tv.connect()
    try:
        if cmd == "check":
            check(dev)
        elif cmd == "disable":
            disable(dev, pkgs_from(sys.argv[2]))
        elif cmd == "enable":
            enable(dev, pkgs_from(sys.argv[2]))
        else:
            settings(dev, sys.argv[2])
    finally:
        dev.close()


if __name__ == "__main__":
    main()
