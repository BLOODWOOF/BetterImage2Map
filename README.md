# BetterImage2Map

Turn any image into Minecraft maps. One command or one screen: the mod converts the picture to
the exact colors a filled map can show, hands you a bundle, and lays it across item frames as
one wall.

Works in single player and on servers. When only the server has the mod, everything still works
through the commands.

## How to use it

Press **M** in game to open the screen, or type `/bi2m`. Both do the same thing:

- **Link** - fetch an image from a URL
- **File** - read a file from the server's folder, or pick one from your own computer
- **Frames** - output size in item frames; leave empty to fit the image (`0.5` means 64x64 pixels)
- **Bundles** - how many copies to give
- **Dither** - sierra-lite, floyd, ordered, atkinson, knoll, family, or off
- **Crop** - drag on the preview to convert only part of the image
- **Background** - white, black, or none behind transparent pixels

The same options exist on the command:

```
/bi2m size 4 2 copies 2 dither ordered background none url https://example.com/picture.png
/bi2m help
```

Right click an item frame holding the bundle to place the whole wall. Shift-punch a placed frame
to take the wall back down. `/bi2m place` and `/bi2m clear` do the same from a command.

## Setup

Requires Minecraft 26.2, Fabric Loader 0.19.3 or newer, and Fabric API. Drop the jar in `mods/`.

Server owners: everything is in `config/betterimage2map.json`. Fetching URLs, local files, size
limits, blocked hosts, and the permission level needed to import can all be changed there.
`/bi2m reload` rereads the file without a restart.

## Building from source

```
./gradlew build
```

The jar lands in `build/libs/`.

## License

MIT
