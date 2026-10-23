# Roadmap

Open items for the custom Fire TV UI (`home/`). Status key: **Open** (not started), **Spike** (feasibility unknown, test first). Feasibility notes are my reading, not tested on the stick yet.

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
- [ ] Write an importer for the Netflix viewing-activity CSV, so older history shows up too.
- [ ] Optional: sync to Trakt so history lives outside the stick.

## 2. Ads in Hotstar, Prime and FanCode

**Goal:** fewer or no ads.

**Reality:** ads inside the video are mostly inserted by the service into the stream, so nothing on the stick can strip them. Modifying the apps is against their terms and breaks on every update, so I'd not do it. What is realistic:

| Ad type | Can we remove it? |
|---|---|
| In-stream video ads (Hotstar, Prime ad tier, FanCode) | No. Paying for the ad-free tier is the only clean fix |
| Banners, home-screen tiles, tracking calls | Mostly yes, with DNS blocking on the network |
| "Skip ad" buttons | An accessibility service can press them, but it is flaky and has the same permission cost as above |

**Tasks**
- [ ] Check which tier each account is on and what the ad-free price would be.
- [ ] Set up DNS-level blocking (Pi-hole or NextDNS) for the stick, and measure which ads disappear in each app.
- [ ] **Spike:** an accessibility service that taps "Skip" where the app shows it. Keep only if it works across two app updates.
- [ ] Extend `debloat/batch4-ads-tracking.pkgs` with anything found that is ad-related and safe to disable.

## 3. Custom volume control

**Problem:** the monitor's own volume has steps 1 to 15. 11 is too quiet and 12 is too loud, with nothing in between.

**Reality:** the stick's volume buttons likely send steps to the monitor over HDMI, so the monitor decides the step size. I haven't confirmed that on this setup. If it holds, finer steps have to come from the stick's side.

**Tasks**
- [ ] **Spike:** find out who controls volume. Check whether the stick's volume changes the monitor's number, or whether the stick has its own 0-15 scale (`adb shell dumpsys audio`).
- [ ] **Spike:** try an audio effect (loudness enhancer or gain) that lowers or raises the stick's output by a fraction of a step. Check it works across apps and on Netflix and Prime, which may bypass it.
- [ ] Choose the design: fix the monitor at 11 and add a software "11.5" gain, or fix it at 12 and add attenuation.
- [ ] Build the control: an on-screen slider in Home, mapped to the remote's volume buttons if Fire OS allows intercepting them.
- [ ] Remember the level per app.

## 4. My teams: schedule and scores

**Goal:** a "My sports" shelf in Home showing next fixture, live score and last result for what I follow, with no app launch needed.

**Following:** cricket (India national team, RCB in the IPL), F1 (Max Verstappen, Red Bull), WTA tennis (Elena Rybakina, Maja Chwalinska), and India at multi-sport games (Olympics, Asian Games, Commonwealth Games).

**Where the data comes from.** Limits below are from third-party pages and not checked against the providers' own docs, so confirm each before building on it.

| Sport | Source | Use | Notes |
|---|---|---|---|
| F1 | [Jolpica](https://api.jolpi.ca/ergast/f1) | Calendar, session times, results, standings | Already in `Feed.java` (`fetchF1`). Reported limit of 500 requests an hour; cache past rounds because they never change |
| Cricket | CricketData (CricAPI) free tier | India fixtures and live scores | About 100 calls a day on the free tier, so poll only on match days |
| Cricket | An IPL-specific free API | RCB fixtures, scores, points table | Smaller services, unclear reliability. Pick one in the spike and keep a fallback |
| Tennis (WTA) | ESPN scoreboard (unofficial): `site.api.espn.com/apis/site/v2/sports/tennis/wta/scoreboard`, optional `?dates=YYYYMMDD` | Live and day-by-day WTA matches | No key. Community-documented, not guaranteed, no published limits; its terms may forbid automated use. Set scores only, no point-by-point. Use the league name `wta`, not a number. First choice to test |
| Tennis (WTA) | [Live Tennis API](https://livetennisapi.com) free key | Backup, and draws | Free tier reported at 30 requests a minute but 100 or 1,000 a day (sources disagree). Covers ATP, WTA, ITF |
| Tennis (WTA) | Google sports results (via SerpApi or ScrapingBee) | Not recommended | Google has no official sports API. These are paid scrapers of Google's pages, so they cost money and can break |

| Multi-sport games | Not chosen yet | India's events, results and medal tally | I found no free, stable API for these. Candidates to test: the Games' own results sites, Wikipedia or Wikidata for medal tables, and ESPN's unofficial feeds. A paid sports-data feed is the fallback |

**Risks**
- Multi-sport games happen only every two to four years and each has its own results site, so a general fetcher is not worth building. A small per-Games adapter, written when the Games are near, is more realistic.
- Maja Chwalinska plays mostly lower-tier events, which free tennis feeds may not cover well. Test her name in each source before choosing one.
- Free cricket tiers are small. The design has to stay inside them: fetch the fixture list once a day, and poll scores only while a followed team is playing.
- All of these are unofficial or small services and can change or vanish. Keep the fetch code behind one interface per sport, so a source can be swapped without touching the UI.

**Design**
- One `Sports` class in `home/`, one small fetcher per sport, all returning the same record: sport, event, team or player, start time, status, score.
- Store followed names in `Taste.java` next to the existing taste data.
- Fetch the fixture lists once a day and cache them on the stick.
- While a followed fixture is live, poll every minute or two and update the shelf.
- Show times in IST.

**Tasks**
- [ ] **Spike, cricket:** try CricketData and one IPL-specific API. Check that they return India matches and RCB matches, how fresh the scores are, and what the call limit really is.
- [ ] **Spike, tennis:** call ESPN's WTA scoreboard during a live tournament and check Rybakina and Chwalinska appear with live set scores; then check the Live Tennis API free key for the same. Choose the source that has both, or use two.
- [ ] **Spike, F1:** confirm Jolpica's calendar gives start times for every session (practice, sprint, qualifying, race) and its results and standings give Max's position and points.
- [ ] **Spike, multi-sport games:** for the next Games on the calendar, find where India's event schedule, results and medal tally can be fetched, and whether it can be filtered to India.
- [ ] Add a Games mode to the shelf: India's medal tally plus its next events, shown only while a Games is on.
- [ ] Define the shared fixture record and the `Sports` class.
- [ ] Build the F1 fetcher first, reusing `fetchF1`, because the source is already working.
- [ ] Build the cricket and tennis fetchers.
- [ ] Add the "My sports" shelf with next fixture, live score and last result.
- [ ] Add live polling that runs only while a followed fixture is on.
- [ ] Optional: a reminder on the screensaver clock, such as "RCB in 30 min".
- [ ] Optional: a button on a fixture that opens the right app (FanCode, Hotstar, Prime) to watch it.

## 5. Live score overlay (top-left)

**Goal:** a small score pill in the top-left corner, on the Home screen and on top of other apps such as YouTube, for the matches I follow that are live right now. Builds on section 4.

**Behaviour**
- Compact pill: LIVE tag, team, score, overs or lap, opponent. Shows the first live match from my follows, and hides when nothing is live.
- Over other apps: same pill, shorter. Fades to half strength after 5 seconds and comes back on a wicket, a goal, a lead change or a score change.
- Expanded card: opened by a remote button. Lists every live followed match, then the next fixture.
- Never takes remote focus, so YouTube controls keep working.

**Routes.** The stick has no server, so a "route" here is one fetch function per sport that returns the same record (sport, event, side A, side B, score, status, last-change time). The URLs are placeholders until the section 4 spikes pick the sources.

| Route | Returns | When it runs |
|---|---|---|
| `live/cricket` | Live India and RCB matches: score, overs, batters | Every 30 to 60 seconds while a followed match is on |
| `countdown/f1` | Next F1 session name and start time, from the Jolpica calendar. No live polling; the countdown is computed on the stick | Calendar fetched once a day |
| `live/tennis` | Live Rybakina and Chwalinska score, set by set | Every 30 seconds during a match |
| `live/games` | India's current event and medal tally | Every minute while a Games is on |
| `fixtures/today` | Today's followed fixtures with start times | Once a day, plus on boot |

**Design**
- A foreground service (not the Home activity) owns the polling and the overlay, because Home is not running while YouTube is on screen.
- The overlay is a system window drawn over other apps. This needs the "display over other apps" permission. On Fire OS it may be grantable with `adb shell appops set com.siddh.home SYSTEM_ALERT_WINDOW allow`; I haven't confirmed that, so test it first.
- Poll only inside a window around a followed fixture's start time, taken from `fixtures/today`. Outside it, do nothing, to stay within the free API limits.
- Colours: dark translucent pill, white text, a red LIVE dot, a small round badge for each side in its team colour. Mockup is in the chat for this item.

**Tasks**
- [ ] **Spike:** confirm a service can draw an overlay over YouTube on the stick, and whether the permission can be granted without a settings screen.
- [ ] **Spike:** check the overlay does not steal remote focus or cover YouTube's own controls.
- [ ] Build the shared record and `fixtures/today`.
- [ ] Build `live/cricket` first, since India and RCB are the main follows.
- [ ] Build the foreground service with the polling window.
- [ ] Build the compact pill, then the expanded card.
- [ ] Add the fade and the pop-back on score change.
- [ ] Add `live/tennis` and `live/games`.
- [ ] Add a setting to turn the overlay off per app.

## 6. F1 session countdown

**Replaces live F1 position.** Live F1 data is the least free of the sources, so instead of tracking the race, the TV tells me when a session is about to start and that it is on. This needs only the session start times from the Jolpica calendar, which Home already fetches.

**Rules**
- **Window:** from 30 minutes before a session (practice, sprint, qualifying, race) until it ends.
- **30 to 10 minutes before:** a pill pops up every 5 minutes (at 30, 25, 20, 15), stays a few seconds, and fades. Text: "Qualifying in 28 min".
- **10 minutes to zero:** the pill stays on screen the whole time, counting down in minutes and seconds: "Qualifying in 7:42".
- **At zero:** the pill changes to "Qualifying underway" and stays for the session.
- **Where:** top-left corner, with the F1 icon and red colour from section 7, on Home and on every app **except FanCode**.
- **FanCode:** never shown while FanCode is in front, because FanCode already shows the session. If I leave FanCode while a session is underway, the "underway" pill appears.
- **While I'm not in FanCode and a session is underway:** show "underway" in the corner. This covers watching YouTube or Netflix while the race is on.

**Session length.** There is no free live feed to say when a session ends, so use a fixed length from the start time and stop the pill when it passes: practice 60 minutes, sprint qualifying 45, qualifying 60, sprint 40, race 2 hours 15. If a session is red-flagged and runs longer, the pill ends early. A tap-to-extend or a dismiss is the fallback.

**Design**
- A foreground service reads the calendar from the cache and updates once a second only inside the 10-minute window. Outside it, it wakes once a minute.
- The service must know which app is in front. Two options to test: Android usage access (`PACKAGE_USAGE_STATS`, may be grantable with `adb shell appops set com.siddh.home GET_USAGE_STATS allow`) or an accessibility service. FanCode's package is `com.fancode.tv`.
- Shares the overlay window from section 5 and respects the same "no remote focus" rule.
- Times are shown in IST. Clock drift is not a concern because the countdown comes from the stick's clock; keep the calendar fresh in case a session is moved.

**Tasks**
- [ ] **Spike:** detect the foreground app on the stick and recognise FanCode.
- [ ] **Spike:** check Jolpica lists every session time for a race weekend, including sprint weekends.
- [ ] Build the countdown logic as a plain class with unit tests: given a session list and the time, return the state (hidden, brief pop, steady countdown, underway) and the text.
- [ ] Wire it to the overlay pill in section 5.
- [ ] Add the FanCode rule and the underway pill.
- [ ] Add a setting to turn it off, and one to pick which sessions it covers (for example race and qualifying only).

## 7. Sports view (full screen)

**Goal:** selecting the score pill opens a full-screen Sports view with everything I follow: live matches, upcoming fixtures, recent results and standings. Mockup is in the chat for this item. Uses the same data and records as sections 4 and 5.

**Layout**
- Four tabs: **Live**, **Fixtures**, **Results**, **Standings**. Left and right move between tabs.
- Three columns, one per sport: cricket (India, RCB), F1 (Verstappen, Red Bull), WTA (Rybakina, Chwalinska). Up and down move between matches.
- Selecting a match opens its detail: full scorecard for cricket, session times and results for F1, set-by-set for tennis.
- When a Games is on, a fourth column shows India's medal tally and events.
- Back returns to where I was, whether Home or the app I left.

**Look: stadium background and liquid glass.** The Sports view sits on a night-stadium scene: floodlight glow at the top corners, dark stands, and a mown-stripe green pitch rising from the bottom, with a dark vignette at the edges. Tabs, the date chip and the three sport columns are frosted glass panels over it: translucent white fill, a bright top edge, a soft inner shadow, and a drop shadow. The focused match card gets a stronger fill and a white ring. Static design preview: `design/sports-view.html` (open it in a browser).

Making it work on a Fire TV Stick 3rd gen (Fire OS 7, Android 9):
- Real background blur (`RenderEffect`, `backdrop-filter`) needs Android 12 and is not available. Fake it: blur the stadium picture once at install time (a stack blur in Java), then draw the matching slice of the blurred picture behind each panel, with the white tint and edge highlight on top. It looks the same because the background never moves.
- The stick's GPU is weak, so keep the background static: no animated floodlights or moving crowd. Pre-render it as one 1080p image.
- Stadium image: render it ourselves (a vector or generated picture) to avoid licence problems. Do not use press photos.
- Match the sport to the background when a match is opened: pitch for cricket, track and grandstand for F1, hard court for tennis.

**Sport icons and colours.** Each sport gets its own icon in a coloured circle, used on the column header, on every match card and on the score pill, so a card is recognisable at a glance across the room.

| Sport | Colour | Icon |
|---|---|---|
| Cricket | Blue | Cricket ball (drawn as a vector; common icon sets have no cricket icon) |
| Formula 1 | Red | Steering wheel |
| WTA tennis | Green | Tennis ball |
| Multi-sport Games | Amber | Medal |
| Standings and rankings | Sport colour | Trophy |

- Draw all icons as vector drawables in `home/app/src/main/res/drawable/`, not emoji, because emoji look different on every device and Fire OS may not render them well.
- Keep colour as a thin top bar on each column plus the icon circle. Do not tint whole cards.

**How it opens.** On Home, selecting the pill opens it. Over another app the pill does not take focus (section 5), so a click can't reach it. Options, in order of preference, to test: a long-press of a remote button, a Home shortcut I set for it, or a "Sports" tile in Home itself.

**Data needs beyond section 5**
- Fixtures for the next 2 to 4 weeks, cached daily.
- Results for the last 2 weeks, stored once a match ends, so they cost no more API calls.
- Standings: F1 drivers and constructors, WTA ranking of the followed players, IPL points table during the season.

**Tasks**
- [ ] Add `fixtures/upcoming`, `results/recent` and `standings` routes to the shared record.
- [ ] Add a small local store for fixtures and results, so the view opens at once and works with a poor connection.
- [ ] Make the stadium background image and the pre-blurred copy, and build a glass panel view that draws its blurred slice.
- [ ] **Spike:** time the glass panels on the stick; drop to a plain translucent fill if scrolling is not smooth.
- [ ] Make per-sport detail backgrounds (cricket pitch, F1 circuit, tennis court).
- [ ] Draw the five sport icons as vector drawables and apply them to the pill, column headers and match cards.
- [ ] Build the Sports activity with the four tabs and three sport columns.
- [ ] Build the match detail screens, cricket first.
- [ ] Make the pill open the view on Home.
- [ ] **Spike:** test the ways to open the view while another app is in front.
- [ ] Add the Games column that appears only during a Games.
- [ ] Add a setting to choose which sports and teams appear, and their order.

## 8. Other ideas to rank later

- [ ] Per-profile home screen (different shelves for each person).
- [ ] Sports shelf: live and upcoming F1 and cricket fixtures, beyond the FanCode artwork.
- [ ] Sleep timer that dims the screen, lowers volume and then turns the stick off.
- [ ] Backup and restore of Home settings and shelf order.
- [ ] Faster boot to Home (measure first).
