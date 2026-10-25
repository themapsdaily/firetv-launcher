"""Netflix viewing history to TMDB ids, for Taste.java's seen lists and taste signals.

Export the history from netflix.com/viewingactivity (Download all) into taste/, then from
D:\\Projects\\fire-tv with API_READ_ACCESS_TOKEN in .env:
    python taste/netflix.py              every taste/*ViewingHistory.csv (Prime's page, copied into the
                                         same Title,Date form, sits beside Netflix's export)

Episodes collapse into their show. Each title is looked up on TMDB (series if it had episodes,
film otherwise) and written to taste/resolved.tsv; lookups that were not an exact title match are
marked "?" for a look. taste/overrides.tsv fixes the rest, one per line, tab separated:
    <title as collapsed>    skip          not the owner's watching (someone else, a sampled pilot)
    <title as collapsed>    tv:1234       the right TMDB id when the lookup picked wrong
    <any other title>       movie:1234    watched outside Netflix; added as if in the history
Then it prints the SEEN_TV / SEEN_MOVIE arrays and the genres and keywords that recur.
"""
import collections
import csv
import glob
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OVERRIDES = os.path.join(HERE, "overrides.tsv")
RESOLVED = os.path.join(HERE, "resolved.tsv")

env = os.path.join(ROOT, ".env")
if os.path.exists(env):
    for line in open(env):
        k, _, v = line.strip().partition("=")
        if k and v:
            os.environ.setdefault(k, v)
TOKEN = os.environ.get("API_READ_ACCESS_TOKEN", "")

# A title segment after the first that starts an episode label: "Season 2", "Limited Series", ...
MARKER = re.compile(r"^(Season|Part|Volume|Limited Series|Chapter|Episode|Book)\b|\s\d+$")


CACHE_FILE = os.path.join(HERE, "cache.json")
CACHE = json.load(open(CACHE_FILE, encoding="utf-8")) if os.path.exists(CACHE_FILE) else {}


def tmdb(path, **params):
    """TMDB GET, cached in taste/cache.json. Some Indian ISPs reset bursts of TMDB connections, so
    requests are paced and retried with a growing wait."""
    url = "https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(dict(params, language="en-US"))
    if url in CACHE:
        return CACHE[url]
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOKEN, "accept": "application/json"})
    for attempt in range(6):
        try:
            time.sleep(0.3)
            with urllib.request.urlopen(req, timeout=20) as r:
                CACHE[url] = json.load(r)
            json.dump(CACHE, open(CACHE_FILE, "w", encoding="utf-8"))
            return CACHE[url]
        except OSError:
            if attempt == 5:
                raise
            time.sleep(5 * 2 ** attempt)


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower().replace("&", "and"))


def collapse(rows):
    """Netflix rows to {title: [episodes, last watched, is series]}."""
    prefixes = collections.Counter()
    for title, _ in rows:
        seg = title.split(": ")
        for i in range(1, len(seg)):
            prefixes[": ".join(seg[:i])] += 1
    out = {}
    for title, date in rows:
        seg = title.split(": ")
        show = title
        for i in range(1, len(seg)):
            if MARKER.search(seg[i]):
                show = ": ".join(seg[:i])
                break
        else:
            # "Show: Episode name" with no label: the prefix repeats, or there are three parts.
            if len(seg) >= 3:
                show = ": ".join(seg[:-1])
            elif len(seg) == 2 and prefixes[seg[0]] >= 2:
                show = seg[0]
        e = out.setdefault(show, [0, date, show != title])
        e[0] += 1
        e[1] = max(e[1], date)
    return out


def lookup(title, series, watched):
    """Best TMDB match as (kind, id, name, year, exact)."""
    q = re.sub(r"\s*\(Raw & Undekha\)", "", title).replace("’", "'")
    kinds = ["tv", "movie"] if series else ["movie", "tv"]
    first = None
    for kind in kinds:
        res = tmdb("/search/" + kind, query=q).get("results", [])
        date_key = "first_air_date" if kind == "tv" else "release_date"
        name_key = "name" if kind == "tv" else "title"
        ok = [m for m in res if (m.get(date_key) or "9999") <= watched]
        exact = [m for m in ok if norm(q) in (norm(m.get(name_key, "")), norm(m.get("original_" + name_key, "")))]
        if exact:
            m = max(exact, key=lambda m: m.get("popularity", 0))
            return kind, m["id"], m[name_key], (m.get(date_key) or "")[:4], True
        if first is None and ok:
            m = ok[0]
            first = (kind, m["id"], m[name_key], (m.get(date_key) or "")[:4], False)
    return first


def main(paths):
    rows = []
    for path in paths:
        with open(path, encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f):
                rows.append((r["Title"].strip(), datetime.strptime(r["Date"], "%m/%d/%y").strftime("%Y-%m-%d")))
    over = {}
    if os.path.exists(OVERRIDES):
        for line in open(OVERRIDES, encoding="utf-8"):
            if line.strip() and not line.startswith("#"):
                t, _, v = line.rstrip("\n").partition("\t")
                over[t.strip()] = v.strip()

    shows = collapse(rows)
    for title, v in over.items():
        if title not in shows and v != "skip":
            shows[title] = [0, "", True]
    seen = {"tv": [], "movie": []}
    genres, keywords = collections.Counter(), collections.Counter()
    names = {}
    with open(RESOLVED, "w", encoding="utf-8", newline="") as out:
        out.write("title\tepisodes\tlast\tkind:id\ttmdb name\tyear\tmatch\n")
        for title, (eps, last, series) in sorted(shows.items(), key=lambda kv: kv[1][1], reverse=True):
            o = over.get(title, "")
            if o == "skip":
                out.write(f"{title}\t{eps}\t{last}\tskip\t\t\toverride\n")
                continue
            if o:
                kind, id_ = o.split(":")
                d = tmdb(f"/{kind}/{id_}")
                hit = (kind, int(id_), d.get("name") or d.get("title"),
                       (d.get("first_air_date") or d.get("release_date") or "")[:4], "override")
            else:
                hit = lookup(title, series, last)
            if not hit:
                out.write(f"{title}\t{eps}\t{last}\t\t\t\tnone\n")
                print("not found:", title, file=sys.stderr)
                continue
            kind, id_, name, year, exact = hit
            match = exact if exact == "override" else ("exact" if exact else "?")
            out.write(f"{title}\t{eps}\t{last}\t{kind}:{id_}\t{name}\t{year}\t{match}\n")
            if id_ not in seen[kind]:
                seen[kind].append(id_)
            # A binge counts for more than a sampled pilot, but not linearly. Titles from outside
            # Netflix have no episode count; count them as watched through.
            w = math.sqrt(eps or 4)
            d = tmdb(f"/{kind}/{id_}", append_to_response="keywords")
            for g in d.get("genres", []):
                genres[g["name"]] += w
                names[("g", g["name"])] = g["id"]
            kw = d.get("keywords", {})
            for k in kw.get("keywords", kw.get("results", [])):
                keywords[k["name"]] += w
                names[("k", k["name"])] = k["id"]

    print("SEEN_TV =", ", ".join(map(str, seen["tv"])))
    print("SEEN_MOVIE =", ", ".join(map(str, seen["movie"])))
    print("\ngenres:", ", ".join(f"{g} {names[('g', g)]} ({v:.1f})" for g, v in genres.most_common(12)))
    print("\nkeywords:", ", ".join(f"{k} {names[('k', k)]} ({v:.1f})" for k, v in keywords.most_common(40)))
    print("\nwrote", os.path.relpath(RESOLVED, ROOT))


if __name__ == "__main__":
    main(sys.argv[1:] or sorted(glob.glob(os.path.join(HERE, "*ViewingHistory.csv"))))
