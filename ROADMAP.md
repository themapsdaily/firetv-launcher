# Roadmap

Open items for the custom Fire TV UI (`home/`). Status key: **Open** (not started), **Spike** (feasibility unknown, test first). Sections 4 to 7 are built and running on the stick (2026-10-24); their open tasks are listed in each. Elsewhere, feasibility notes are my reading, not tested on the stick yet.

## 1. Watch history across apps

**Goal:** one "what I watched" list from Netflix, Prime Video, Hotstar and others, shown in Home.

**Reality:** none of these apps offers a public API for your history. Three routes exist:

| Route | What you get | Cost |
|---|---|---|
| Log it ourselves from Android media sessions | Title, app and time, for anything you play on the stick from now on. `Continue watching` already reads media-session art, so the hook exists | Needs a persistent log (small local DB) and a history screen. No past history |
| Import the apps' own exports | Past history. Netflix has a viewing-activity CSV download; Amazon and Disney+ Hotstar have data-request exports | Manual, per app, and the formats change |
| Accessibility service that reads on-screen text | Works when media sessions give no title | Fragile, needs a special permission, breaks on app updates |

**Tasks**
- [ ] **Spike:** play one title in each of Netflix, Prime, Hotstar and FanCode, and record what each exposes through the media session (title, duration, position). Decide per app whether the live log is enough.
- [ ] Add a local history store (app, title, start time, minutes watched).
- [ ] Add a History shelf or screen in Home.
- [x] Importer for past history (2026-10-25): `taste/netflix.py` reads Netflix's viewing-activity CSV and Prime's history page (copied into the same Title,Date form), merges episodes into shows and finds each on TMDB. The accounts are shared, so the owner filters out what someone else watched; those, titles watched elsewhere and wrong matches go in `taste/overrides.tsv`. The exports and lookups stay on the PC, gitignored.
- [x] History on TMDB: `taste/tmdb_list.py` keeps a private "Watched" list on the owner's account in step with it and sends the ratings from `taste/ratings.csv` (168 titles rated, 2026-10-25).
- [ ] Feed the ratings into `Taste.java`: seen lists from the rated titles, keywords from the best-rated ones, and the horror rule (the owner rates some horror highly).
- [ ] Optional: sync to Trakt so history lives outside the stick.

**Streaming shelves rotate daily (2026-10-25):** `Rotation.java` picks each app's five afresh every calendar day. A title shown one day rests the next and may return the day after, and a nudge that changes daily reorders the candidates. Tested off the TV (`RotationTest`) and on the emulator by moving its clock a day; on the stick since 2026-10-25.
- [ ] Watch the shelves on the stick for a few days: they change each morning, and nothing repeats the next day.

## 2. Ads in Hotstar, Prime, FanCode, Airtel Xstream and SonyLIV

**Goal:** fewer or no ads.

**Reality:** ads inside the video are mostly inserted by the service into the stream, so nothing on the stick can strip them. Modifying the apps is against their terms and breaks on every update, so I'd not do it. What is realistic:

| Ad type | Can we remove it? |
|---|---|
| In-stream video ads (Hotstar, Prime ad tier, FanCode) | No. Paying for the ad-free tier is the only clean fix |
| Banners, home-screen tiles, tracking calls | Mostly yes, with DNS blocking on the stick |
| "Skip ad" buttons | An accessibility service can press them, but it is flaky and Guard would have to start reading screen content |

**DNS blocking (2026-10-25):** personalDNSfilter already runs on the stick (dumbtv), but its list covers only Amazon's trackers. Opening each app's home screen (nothing played) and reading the filter's traffic log showed these unblocked hosts. Hotstar: `bifrost-api.hotstar.com` (its ad platform) and `app-measurement.com` (Firebase Analytics). FanCode: `vmax-ads.fancode.com` (its ad SDK), AppsFlyer, New Relic, Firebase logging and Crashlytics. Airtel Xstream: `adconfig.wynk.in` (its ad configuration), `events.airtel.tv` and Flurry. SonyLIV: `evt.sonyliv.com`, Google Analytics, CleverTap, AppsFlyer and Firebase. Prime Video's home made no ad calls; its ads are in the stream, and its telemetry is already in dumbtv's list. All are now in `debloat/ads.hosts`. `bash debloat/ads.sh` writes them into the filter's `additionalHosts.txt` as a marked block (`--undo` removes it, `--check` tests each host). The block is live since a reboot on 2026-10-25.

**Tasks**
- [ ] Check which tier each account is on and what the ad-free price would be.
- [x] DNS-level blocking for the stick: personalDNSfilter plus `debloat/ads.hosts` (2026-10-25).
- [x] Rebooted and checked (2026-10-25): all hosts blocked. FanCode works and showed no ads.
- [x] Airtel Xstream, SonyLIV and Prime Video added and rebooted (2026-10-25): all 16 hosts blocked, and all three apps open to their home screens.
- [ ] Use Hotstar, YouTube, Airtel Xstream and SonyLIV normally with the list on, including playback. If one breaks, put `!` in front of its host or run `ads.sh --undo`.
- [ ] Play a title in Hotstar and in Prime with the traffic log on, and add any ad host the home screens did not show. Prime made no ad lookups on its home screen.
- [ ] **Spike:** tap "Skip" where the app shows it. Guard would need `canRetrieveWindowContent`, so it would read every app's screen. Keep it only if it works across two app updates.
- [ ] Extend `debloat/batch4-ads-tracking.pkgs` with anything found that is ad-related and safe to disable. Nothing new found yet.

## 3. Custom volume control

**Problem:** the volume has steps 1 to 15. 11 is too quiet and 12 is too loud, with nothing in between.

**Finding (2026-10-25):** the steps are the stick's own, not the monitor's. Audio goes over Bluetooth to a Mivi speaker, the stick's Bluetooth media volume sat at 11/15, and every volume press in `dumpsys audio` came from the remote through the stick's window manager.

**Built (2026-10-25): half steps, `Volume.java` in Guard.** Each press of the remote's volume keys moves half a step. "11½" is the stick at 12 with a 1.5 dB cut on the whole output mix (a session-0 `DynamicsProcessing` effect, with an `Equalizer` fallback). On the TV the effect sits on the A2DP output thread. The level is remembered per app, and set again only within two steps of the current level, so a level kept on another output never blasts.
- Android TV builds hand the volume keys to the window manager before any accessibility filter (AOSP `KeyboardInterceptor`), so Guard can't take them. Instead Fire OS makes its whole step, Volume hears the volume-changed broadcast, and it puts the stick back on the step the half level needs. That step is heard for a moment before the correction.
- Fire OS's own volume bar shows whole steps (11½ shows as 12). It can't be hidden, and a pill of our own on top of it was one bar too many, so there is none.
- A press up from 14 goes straight to 15, because Fire OS sends no broadcast once it is at 15.
- Settings: `--ez volume false` turns it off; `--es volume_half_db 2` changes the cut.

**Tasks**
- [x] **Spike:** find out who controls volume (2026-10-23, 2026-10-25: the stick, over Bluetooth).
- [x] **Spike:** an effect that cuts the output by a fraction of a step. `DynamicsProcessing` on session 0 attaches on Fire OS 7 (2026-10-25).
- [x] Design: the stick one step up, with a cut for the half step.
- [x] Build it, following the remote's volume keys (they can't be intercepted, see above).
- [x] Remember the level per app.
- [x] 1.5 dB sounds like half a step (owner, 2026-10-25).
- [ ] Check the cut applies in Netflix, Prime, YouTube and Spotify (Netflix and Prime might bypass the mixer).
- [ ] If the brief blip on each press is annoying, try a Home-side slider instead of the remote keys.

## 4. My teams: schedule and scores

**Goal:** fixtures, live scores and results for what I follow, on the TV, with no app launch needed.

**Following** (`Taste.java`): cricket (India, RCB in the IPL), F1, WTA tennis (Elena Rybakina, Maja Chwalinska), and India at multi-sport games (Olympics, Asian Games, Commonwealth Games).

**Status (2026-10-24): built**, in `Sports.java`. It feeds the score pill (section 5), the F1 countdown (section 6) and the Sports view (section 7). Games mode is the main thing left.

**Sources in use.** All keyless. ESPN's feeds are unofficial and community-documented, with no published limits, so they may change without notice.

| Sport | Source | Notes |
|---|---|---|
| Cricket | ESPN cricket scorepanel, `site.api.espn.com/apis/site/v2/sports/cricket/scorepanel?dates=YYYYMMDD` | Returns about 25 matches ending on that date, so it is asked one day at a time for 6 days back and 21 ahead. Chosen over CricketData's free tier (about 100 calls a day) |
| Tennis (WTA) | ESPN WTA scoreboard (`tennis/wta/scoreboard`) | Draws appear a day or two ahead, so it is asked for yesterday to two days ahead. Set scores only |
| F1 | [Jolpica](https://api.jolpi.ca/ergast/f1) | Calendar with every session time, this and next season; last 4 race winners and the last podium |

**How it works**
- One record (`Sports.Fixture`) for every sport: event, stage, both sides, scores, venue, state, start time. One fetcher per sport, so a source can be swapped without touching the screens.
- Fixtures and results are fetched once a day (retry after 30 minutes on failure) and cached in `files/sports.json`, so the screens work offline. Finished matches stay for 14 days at no extra cost.
- Live scores are polled only from 10 minutes before a followed match until it ends: cricket every 45 seconds, tennis every 60.
- F1 sessions get their state (upcoming, on, done) from the clock, as there is no free live feed.
- Times are in IST.

**Tasks**
- [x] Pick sources: ESPN for cricket and tennis, Jolpica for F1.
- [x] Shared fixture record and the `Sports` class.
- [x] F1, cricket and tennis fetchers.
- [x] Live polling that runs only while a followed match is on.
- [x] Instead of a "My sports" shelf: the score pill (section 5) and the Sports view (section 7).
- [ ] **Check RCB when the IPL starts.** The scorepanel has only shown internationals so far, so confirm IPL matches come through.
- [ ] **Check Chwalinska.** She plays mostly lower-tier events; confirm ESPN lists her matches, or add a second tennis source.
- [ ] **Check a sprint weekend** in Jolpica (sprint qualifying and sprint times).
- [ ] **Spike, multi-sport games:** for the next Games, find where India's schedule, results and medal tally can be fetched and filtered to India. No free, stable API found yet; candidates are each Games' results site, Wikipedia or Wikidata, and ESPN.
- [ ] Games mode: India's medal tally and next events, only while a Games is on.
- [ ] Live F1 position (for example "Max P3, lap 21/57"). Jolpica has results only after a race; this needs a free live timing source, and none has been found yet.
- [ ] Optional: a reminder on the screensaver, such as "RCB in 30 min".
- [ ] Optional: open the right app (FanCode, Hotstar, Prime) from a fixture.

## 5. Live score overlay (top-left)

**Goal:** a small score pill in the top-left corner, on Home and on top of other apps, for followed matches that are live.

**Status (2026-10-24): built**, in `Scores.java`, running inside Guard. The expanded card and a test on the real remote are left.

**Behaviour**
- One short line after a dot in the sport's colour, in Barlow Condensed at 22 px: `NZ 123/5 (11.2) vs IND` (batting side first), `Rybakina 6-3 2-6 7-5 vs Shnaider`. Without a live match it shows the F1 countdown (section 6), else nothing.
- Fades to half strength after 5 seconds; comes back when the score or state changes.
- Never takes remote focus.
- **Close it:** over another app, the menu button (three lines) hides it until Home is back in front. Over the app, menu reaches the app as usual once the pill is hidden.
- Hidden on the Sports view, which shows it all already.

**How it works**
- Guard, the accessibility service that replaces Amazon's home, hosts the pill as a `TYPE_ACCESSIBILITY_OVERLAY` window. That needs no "display over other apps" permission, is always running, and never takes focus.
- Guard's window events say which app is in front (used for FanCode in section 6), and its key filter (`flagRequestFilterKeyEvents`) sees the menu button. Nothing on screen is read.
- Settings, as Home launch extras: `--ez scores false` turns it off; `--es scores_hide <pkg,pkg>` keeps it off over those apps.

**Tasks**
- [x] Overlay over other apps without a permission screen (accessibility overlay).
- [x] Check it does not take remote focus.
- [x] Today's fixtures and live cricket and tennis.
- [x] Polling inside the match window, from Guard instead of a separate foreground service.
- [x] Compact pill, with the fade and pop-back.
- [x] Turn it off, or off per app.
- [x] Close it with the menu button until back on Home (tested on the emulator).
- [ ] **Test on the real remote:** during a live match, open Netflix and press menu.
- [ ] Expanded card, opened by a remote button: every live followed match, then the next fixture.
- [ ] Games line, once Games mode exists (section 4).

## 6. F1 session countdown

**Replaces live F1 position.** No free live F1 feed, so the TV says when a session is about to start and that it is on, from Jolpica's session times.

**Status (2026-10-24): built**, in `Countdown.java` (11 unit tests) and shown by the pill.

**Rules**
- From 30 to 10 minutes before a session (practice, sprint qualifying, sprint, qualifying, race), the pill pops up for 8 seconds at 30, 25, 20 and 15 minutes: "Qualifying in 28 min".
- From 10 minutes, it stays and counts down in minutes and seconds: "Qualifying in 7:42".
- At the start it says the session is underway, for a fixed length: practice 60 minutes, sprint qualifying 45, qualifying 60, sprint 40, race 2 hours 15. A red-flagged session that runs longer ends early.
- Never while FanCode (`com.fancode.tv`) is in front; leaving FanCode during a session shows "underway".

**Tasks**
- [x] Detect the foreground app and recognise FanCode (Guard's window events).
- [x] Countdown logic as a plain class with unit tests.
- [x] Wired to the pill, with the FanCode rule and the underway state.
- [x] Settings: `--ez scores false` turns it off with the pill; `--es f1_sessions Race,Qualifying` limits it to those sessions.
- [ ] Check a sprint weekend's sessions (section 4).

## 7. Sports view (full screen)

**Goal:** a full-screen view of everything I follow: live matches, upcoming fixtures and recent results.

**Status (2026-10-24): built**, in `Scoreboard.java`, after the owner's `sports-screen.html` design. Opened from the Sports tile in Home's status bar. Full match details and opening it over other apps are left.

**Layout**
- Two tabs: **Fixtures** (live matches first) and **Results**. No standings: not wanted (2026-10-24).
- Three columns, one per sport: Cricket, Formula 1, WTA Tennis. Up and down move between cards and scroll the column; left and right move between sports. Cards near the bottom fade out.
- Fixture card: tournament top-left, stage top-right, both sides with a code chip, day and time on the right, venue in the footer. The next match has an accent border; later cricket fixtures are one-line rows.
- F1: this weekend's sessions with day and time, the next one marked, the race on a red tint; then the next rounds. Results show the winner and podium with gaps, then earlier winners.
- Results: scores with overs for cricket, the result spelled out ("India won by 4 wickets, 1 ball left"); set boxes for tennis, with RET for a retirement.
- Select opens a short detail panel (date, match, score, venue, status; or the F1 sessions or podium). Back closes it, then the view.

**Look.** Near-black, one accent per sport (cricket #4C9BFF, F1 #FF4D3D, tennis #C8F24A), Barlow Condensed for labels and numbers, Barlow for text (both OFL, in `assets/fonts`). A white ring marks focus. The earlier stadium background and glass panels were dropped for this design, which needs no blur and is light on the stick's GPU.

**Icons** (vector drawables): cricket ball, F1 flag, tennis ball, and a trophy for the Sports tile.

**Tasks**
- [x] Local store for fixtures and results, so the view opens at once and works offline.
- [x] Sports activity with two tabs and three sport columns.
- [x] Sport icons on the column headers.
- [x] Open it from Home (the status bar's Sports tile; the pill never takes focus).
- [x] Basic match detail panel.
- [ ] Full cricket scorecard in the detail (batting and bowling).
- [ ] IPL points table during the season, if wanted.
- [ ] **Spike:** open the view while another app is in front: a long-press of a remote button, or the expanded card's button (section 5).
- [ ] Games column, only during a Games.
- [ ] On-TV setting for which sports and teams appear, and their order (today they are in `Taste.java`).

## 8. Known gaps

Smaller items, roughly in priority order.

### Performance (the 1 GB stick lags)

- [x] **Release pictures in the background.** Home drops the gallery's pictures in `onStop` and on `TRIM_MEMORY_UI_HIDDEN`, and reloads them on return. Measured on the stick (2026-10-24): 73 to 67 MB PSS on Home, 83 to 76 MB with another app in front.
- [x] **Free GPU caches in the background.** Guard's accessibility binding keeps Home's process counted as in use, so Android never sends `TRIM_MEMORY_UI_HIDDEN` and never trims its GPU caches. `Tidy.gpu()` makes the same trim call itself (the hidden `WindowManagerGlobal.trimMemory`) a second after Home stops. Measured on the stick (2026-10-25) with Prime Video in front: graphics 31.5 to 17 MB, total 62 to 42 MB PSS.
- [ ] **Shrink the launcher's memory further.** The remaining 17 MB of graphics could drop if Guard and its pill ran in their own process (`android:process`), but a second process costs about 5 to 10 MB itself. Needs care, because Guard and Home share static state (`Guard.shown()`, `Guard.amazon()` pausing Guard, `Home.lastTint`, the `Sports` cache, and settings in shared preferences).
- [ ] **Lighter animations on low memory.** Turn off dock magnification when `ActivityManager.isLowRamDevice()` is true or available RAM is under 250 MB. (The screensaver's depth effect was removed on 2026-10-24.)
- [ ] **Idle Spotify keeps 40 to 50 MB.** Its player service stays in the foreground after the music stops, so `killBackgroundProcesses` can't touch it. Tried 2026-10-24: a Stop media key sent to its `RemoteControlReceiver` reaches it, but the service stays; an app can't force-stop another. Left: `install.sh --tidy`, which force-stops non-playing media apps over ADB.
- [ ] **Lag while installing apps.** With about 60 MB free at idle and 176 MB swapped, an Appstore download and install makes the whole stick lag. The items above are the parts we control.
- [ ] **`com.amazon.tv.livetv` idles at about 50 MB.** Check whether HDMI/input needs it. If not, add it to a debloat batch 9.

### Features

- [ ] **Open shelf items.** Selecting a slide should open it in its app: Spotify URIs, and Netflix, Prime or JioHotstar title deep links where TMDB has an ID.
- [ ] **Refresh the Spotify shelf automatically.** `Taste.MUSIC` is a fixed list today. Add a small script that regenerates it from the Spotify Web API (top items, short and medium term) before `install.sh`.
- [ ] **On-TV settings.** Set name, city and hidden apps from a settings panel instead of `am start` extras.
- [ ] **Test the Amazon Music button.** The remote's Amazon Music button should open Spotify through Guard, but nobody has pressed it on a real remote yet. Since 2026-10-24 Guard skips the switch when the store page followed a select press, because opening any app's store page (for example Airtel Xstream's) also goes through AppLaunchActivity and opened Spotify by mistake. Also check that opening a store page no longer does that.
- [ ] **More taste.** Extend `Taste.java` as more shows come up. Maybe let the watch history from section 1 feed it.

### Project

- [ ] **Release signing.** Release builds use the debug key. Add a real keystore, gitignored and passed through the environment.
- [ ] **CI.** A GitHub Actions workflow that builds the APK on push and attaches it to tagged releases.
- [ ] **Fix the deprecation warning** in `Home.java` (`-Xlint:deprecation`).
- [ ] **Standalone debloat.** `debloat/fire.py` needs a cleanyourtv clone. Vendor its ADB client (with its license) or pin a commit.
- [ ] **Screenshots in the README.** Show the dock, the Spotify shelf and the screensaver.
- [x] **Local testing.** `home/emulator.sh` runs an Android 9 TV emulator at 1080p and installs Home on it, so changes are checked before they reach the stick. It has no Amazon home, FanCode or Spotify, so those parts are checked on the stick. Note: `adb shell input` keys skip accessibility key filters; send keys with `adb emu event send` to test Guard's.
- [ ] **Fixed address for the stick.** It moved from 192.168.1.5 to 192.168.1.3 by DHCP (2026-10-24). Reserve an address for it in the router, or have `install.sh` find it.
- [ ] **Switch ADB off after a deploy.** Add `install.sh --adb-off`, which turns wireless debugging off when you're done.

## 9. Other ideas to rank later

- [ ] Per-profile home screen (different shelves for each person).
- [ ] Sleep timer that dims the screen, lowers volume and then turns the stick off.
- [ ] Backup and restore of Home settings and shelf order.
- [ ] Faster boot to Home (measure first).
