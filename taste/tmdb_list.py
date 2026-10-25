"""Puts the resolved viewing history in a private TMDB list on the owner's account, to rate there.

Run from D:\\Projects\\fire-tv after taste/netflix.py:
    python taste/tmdb_list.py auth      prints a link; approve it on themoviedb.org while logged in
    python taste/tmdb_list.py token     after approving: saves TMDB_USER_TOKEN to .env
    python taste/tmdb_list.py push      creates "Watched" (private) once, then makes it match
                                        taste/resolved.tsv: adds new titles, removes skipped ones
    python taste/tmdb_list.py rate      sends the ratings filled in taste/ratings.csv

The user token can write to the account, so it lives only in the gitignored .env.
"""
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ENV = os.path.join(ROOT, ".env")
RESOLVED = os.path.join(HERE, "resolved.tsv")
PENDING = os.path.join(HERE, "request_token.txt")
RATINGS = os.path.join(HERE, "ratings.csv")
LIST_NAME = "Watched"

if os.path.exists(ENV):
    for line in open(ENV):
        k, _, v = line.strip().partition("=")
        if k and v:
            os.environ.setdefault(k, v)


def v4(method, path, token, body=None):
    req = urllib.request.Request("https://api.themoviedb.org/4" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token,
                                          "Content-Type": "application/json;charset=utf-8"})
    # Some Indian ISPs reset bursts of TMDB connections; wait it out (push is safe to re-run).
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError:
            raise
        except OSError:
            if attempt == 5:
                raise
            time.sleep(5 * 2 ** attempt)


def set_env(key, value):
    lines = [l for l in open(ENV).read().splitlines() if not l.startswith(key + "=")] if os.path.exists(ENV) else []
    open(ENV, "w").write("\n".join(lines + [key + "=" + value]) + "\n")


def auth():
    t = v4("POST", "/auth/request_token", os.environ["API_READ_ACCESS_TOKEN"], {})["request_token"]
    open(PENDING, "w").write(t)
    print("Approve within 15 minutes:\nhttps://www.themoviedb.org/auth/access?request_token=" + t)


def token():
    r = v4("POST", "/auth/access_token", os.environ["API_READ_ACCESS_TOKEN"],
           {"request_token": open(PENDING).read().strip()})
    set_env("TMDB_USER_TOKEN", r["access_token"])
    set_env("TMDB_ACCOUNT_ID", r["account_id"])
    os.remove(PENDING)
    print("saved TMDB_USER_TOKEN for account", r["account_id"])


def push():
    tok, account = os.environ["TMDB_USER_TOKEN"], os.environ["TMDB_ACCOUNT_ID"]
    lists = v4("GET", f"/account/{account}/lists", tok).get("results", [])
    mine = next((l for l in lists if l["name"] == LIST_NAME), None)
    if mine:
        list_id = mine["id"]
    else:
        list_id = v4("POST", "/list", tok, {"name": LIST_NAME, "iso_639_1": "en", "public": False,
                                            "description": "Netflix and Prime history plus titles watched elsewhere, to rate."})["id"]
        print("created list", list_id)
    have, page = set(), 1
    while True:
        d = v4("GET", f"/list/{list_id}?page={page}", tok)
        have |= {f"{m['media_type']}:{m['id']}" for m in d.get("results", [])}
        if page >= d.get("total_pages", 1):
            break
        page += 1
    want = {k for k in resolved().values()}
    item = lambda k: {"media_type": k.split(":")[0], "media_id": int(k.split(":")[1])}
    add, drop = [item(k) for k in want - have], [item(k) for k in have - want]
    for method, items, verb in (("POST", add, "added"), ("DELETE", drop, "removed")):
        if items:
            r = v4(method, f"/list/{list_id}/items", tok, {"items": items})
            failed = [x for x in r.get("results", []) if not x.get("success")]
            print(f"{verb} {len(items) - len(failed)}, failed {len(failed)}", failed or "")
    if not add and not drop:
        print("list already matches")
    print(f"https://www.themoviedb.org/list/{list_id}")


def resolved():
    """Title to "kind:id" for every kept title in taste/resolved.tsv."""
    out = {}
    for row in list(open(RESOLVED, encoding="utf-8"))[1:]:
        cols = row.rstrip("\n").split("\t")
        if ":" in cols[3]:
            out[cols[0]] = cols[3]
    return out


def rate():
    """Sends taste/ratings.csv's ratings to the account. TMDB takes 0.5 to 10 in half steps."""
    if not os.environ.get("TMDB_SESSION_ID"):
        sid = v3("POST", "/authentication/session/convert/4", None,
                 {"access_token": os.environ["TMDB_USER_TOKEN"]})["session_id"]
        set_env("TMDB_SESSION_ID", sid)
        os.environ["TMDB_SESSION_ID"] = sid
    ids = resolved()
    sent, missing = 0, []
    with open(RATINGS, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            value = r["Your rating (1-10)"].strip()
            if not value:
                continue
            if r["Title"] not in ids:
                missing.append(r["Title"])
                continue
            kind, id_ = ids[r["Title"]].split(":")
            v = min(10.0, max(0.5, round(float(value) * 2) / 2))
            v3("POST", f"/{kind}/{id_}/rating", os.environ["TMDB_SESSION_ID"], {"value": v})
            sent += 1
    print(f"rated {sent}; not on TMDB or held: {missing}")


def v3(method, path, session, body):
    url = "https://api.themoviedb.org/3" + path + (f"?session_id={session}" if session else "")
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + os.environ["API_READ_ACCESS_TOKEN"],
                                          "Content-Type": "application/json;charset=utf-8"})
    for attempt in range(6):
        try:
            time.sleep(0.3)
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError:
            raise
        except OSError:
            if attempt == 5:
                raise
            time.sleep(5 * 2 ** attempt)


if __name__ == "__main__":
    {"auth": auth, "token": token, "push": push, "rate": rate}[sys.argv[1]]()
