# Screen Mirror watchapp

Pebble C watchapp for the Pebble Time 2 (emery). It receives 1-bit 200×228
frames over AppMessage and sends touch/button commands back to the Android
companion app.

## Build

```bash
pebble build
# build/watchapp.pbw
```

## Test in QEMU

```bash
pebble install --emulator emery --vnc
pebble screenshot --emulator emery --vnc --no-open screenshot.png
```

Send a synthetic frame (from the repository root, in WSL):

```bash
python tools/send_test_frame.py --pebble /opt/pebble-venv/bin/pebble
```

## Controls

- **Touchscreen**: tap / swipe / free-form circle or S; the complete polyline
  is replayed on the phone when the finger lifts.
- **UP / DOWN**: short press pans the viewport; long UP jumps to the top and long
  DOWN jumps to the bottom (viewport mode).
- **SELECT**: Android Home.
- **Double SELECT**: Android Recents.
- **Long SELECT**: toggle viewport/fit mode.
- **BACK**: Android Back.
- **Long BACK**: exit the watchapp (system behavior).

When the phone stops mirroring (or the connection times out), the app clears the
last frame and returns to its splash screen.

## Icon

`art/menu_icon.svg` is rasterized to `resources/images/menu_icon.png` at 25×25
and registered as `IMAGE_MENU_ICON` with `menuIcon: true`. Rebuild the PBW after
changing it; the launcher icon comes from that resource.

The watch talks to the official Pebble app through PebbleKit Classic. It does
**not** include PKJS and does **not** declare a `companionApp`, so the official
app uses its legacy broadcast bridge. See `docs/PROTOCOL.md`.
