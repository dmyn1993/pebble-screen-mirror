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
- **UP / DOWN**: pan the viewport in viewport mode.
- **SELECT**: tap the center of the viewport.
- **Double SELECT**: Android Recents.
- **Long SELECT**: Android Home.
- **BACK**: Android Back.
- **Long BACK**: exit the watchapp (system behavior).

## Icon

`art/menu_icon.svg` is rasterized to `resources/images/menu_icon.png` at 25×25
and registered as `IMAGE_MENU_ICON` with `menuIcon: true`. Rebuild the PBW after
changing it; the launcher icon comes from that resource.

The watch talks to the official Pebble app through PebbleKit Classic. It does
**not** include PKJS and does **not** declare a `companionApp`, so the official
app uses its legacy broadcast bridge. See `docs/PROTOCOL.md`.
