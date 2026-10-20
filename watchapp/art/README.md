# Watchapp artwork

`menu_icon.svg` is the editable source for the launcher icon. It is rasterized
to the 25x25 PNG consumed by the Pebble resource compiler:

```bash
rsvg-convert -w 25 -h 25 -o ../resources/images/menu_icon.png menu_icon.svg
```

The icon is registered as `IMAGE_MENU_ICON` with `menuIcon: true` in
`../package.json`. `menu_icon_250.png` is only a large preview; it is not
packaged into the PBW.
