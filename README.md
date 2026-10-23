# fire-tv

An Apple TV–style home screen and a reversible debloat for an Amazon Fire TV Stick (3rd gen, Fire OS 7), with no root.

- **`home/`**: *Home* (`com.siddh.home`), a plain-Java launcher with no libraries. It has a gallery shelf with a glass dock, artwork for each app (TMDB picks for streaming apps, F1 times for FanCode, your top music plus India's charts for Spotify), a "Continue watching" shelf built from media-session art, and an iOS-style screensaver with clock and weather. `Tidy` closes background apps when memory runs low.
- **`debloat/`**: `fire.py` disables Amazon packages in batches with `pm disable-user`, applies privacy settings, and can undo every step.

## Install

Needs `adb`, a JDK 17 and the Android SDK (platform 34).

```bash
export ADB=/path/to/adb ADB_SERIAL=192.168.1.x:5555 JAVA_HOME=/path/to/jdk-17
bash home/install.sh          # build, install, take over HOME, set the screensaver
bash home/install.sh --undo   # hand HOME back
```

Optional:
- `API_KEY=<TMDB v3 key>` in `.env` turns on title art for the streaming apps.
- `home/app/src/main/assets/avatar.jpg` is shown in the clock pill. Without it you get your initial.
- `home/art/<package>/` holds pictures or clips that `install.sh` pushes to that app's shelf.

Your taste (shows, keywords, top music) lives in `home/app/src/main/java/com/siddh/home/Taste.java`.

## Credits

- [dumbtv](https://github.com/humblemuzzu/dumbtv) (MIT): `Guard.java` is ported from its launcher, and its never-disable list guides the debloat.
- [cleanyourtv](https://github.com/Gathin23/cleanyourtv) supplied the pure-Python ADB client that `debloat/fire.py` runs on. Clone it next to `debloat/` and set up its `tools/venv` first.
- Outfit font: SIL Open Font License, see `home/FONT-LICENSE-OFL.txt`.
- Data: TMDB, Apple Marketing Tools RSS, Jolpica F1, Wikimedia Commons, Open-Meteo.

## License

MIT. See `LICENSE`.
