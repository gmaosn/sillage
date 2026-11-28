# Where Sillage runs, and how to get a game there

One game, one source file; what changes from one system to the next is the engine's backend and the way the game is packed. Everything below is done from the Linux computer where the game is written; `demo` stands for any game of the project (`./sillage games` lists them). The table says what has been tried, and on what: read the last column before promising a platform to someone.

| System | Backend | Pack with | Tried on |
|---|---|---|---|
| Linux x86-64 | Vulkan (OpenGL ES through EGL without it), Wayland or X11, ALSA, pads | `./sillage package GAME` (AppImage) | a real desktop (Intel Arc), daily; OpenGL ES in a Wayland window (isolated KWin) and an X11 one (Xvfb) |
| Raspberry Pi 4, 5, 500 (64-bit OS) | the same, ARM build | `./sillage package GAME --arch aarch64` | an emulated ARM processor with a software renderer; **not yet on a real Pi** |
| FreeBSD 15 x86-64 | Vulkan, X11 or Wayland, OSS sound; keyboard and mouse only | `./sillage package GAME --portable` | a virtual machine with a software renderer (window, keyboard, sound device) |
| NetBSD 11 x86-64 | through its Linux emulation: X11, software Vulkan, sound; keyboard and mouse only | `./sillage package GAME --portable` | a virtual machine (window, sound device); slow, see below |
| A web page | WebAssembly, WebGL 2, Web Audio, touch | `./sillage web GAME` | headless Chromium with a software renderer |
| Android | OpenGL ES 3, touch, AudioTrack | `./kotlin build -m GAME-android` (APK) | the emulator, software renderer |
| Windows 10 or 11, 64 bits | Vulkan, a Win32 window, waveOut sound; keyboard and mouse only | `./sillage package GAME --os windows` (a folder and its .zip) | Wine on Linux (window, keyboard, sound sent to a null device, the Windows Java runtime); **not yet on a real Windows** |
| macOS, iOS | none | — | — |

## Raspberry Pi

A Pi 4, 5 or 500 with the 64-bit Raspberry Pi OS (Bookworm or later) has what the engine calls: Vulkan (Mesa's V3DV driver, installed by default), Wayland, ALSA; and OpenGL ES 3.1, which draws the game if Vulkan will not start. A Pi Zero, 1, 2 or 3 has neither Vulkan nor OpenGL ES 3 and is out of reach.

On the x86 computer where the game is developed (Docker makes the start-up cache; without it the AppImage is made without, and starts a little slower):

```sh
./sillage package demo --arch aarch64        # build/package/demo-aarch64/demo-aarch64.AppImage, about 30 MB
```

On the Pi: copy the file, `chmod +x demo-aarch64.AppImage`, run it. Nothing to install: the AppImage carries its own Java runtime.

The engine picks the `medium` quality by itself on a Pi (shadow maps of 1024 instead of 2048). If the picture is slow:

```sh
./demo-aarch64.AppImage --quality low        # no anti-aliasing either
```

and lower the screen's resolution: the picture is drawn at the window's size, and a Pi's GPU is small. F3 shows the frames per second; the line `SILLAGE_RUN_OK … cpu=… gpu=…` printed when the game ends gives the times per frame — worth sending back when reporting how it runs.

What emulation could not tell: the speed, and whether the Pi's driver accepts everything the renderer asks (16-bit float pictures with 4 samples, three shadow layers). The renderer now asks the card what it can do instead of assuming, and says clearly what is missing if something is; if Vulkan fails all the same, the game is drawn with OpenGL ES and one line says why.

The first thing to run on a Pi, and to send back:

```sh
./demo-aarch64.AppImage --doctor            # the system, Vulkan (V3DV), Wayland, OpenGL ES, sound, memory; then timings
```

It prints a short report (each check with a time limit, nothing heard from the speakers), its last lines saying what to install when something is missing (on Raspberry Pi OS: `sudo apt install mesa-vulkan-drivers` for Vulkan).

## Windows

On the development computer (Linux):

```sh
./sillage package demo --os windows        # build/package/demo-windows/demo-windows.zip, about 30 MB
```

The zip holds a folder with the game's jars, a Java runtime for Windows linked for it, and `demo.bat`. On the Windows computer: unzip, double-click `demo.bat` (no console window; from a terminal, `demo.bat --help` lists the options). Nothing to install besides the graphics card's own driver, which brings Vulkan (Intel, AMD and Nvidia drivers of the last ten years do).

- The window is a plain Win32 one; keys are read by their place on the keyboard, as everywhere (ZQSD on an AZERTY keyboard is WASD on a QWERTY one), text through Windows itself (layouts, dead keys). `SILLAGE_KEYS=1` prints each key as Windows reports it.
- Sound goes to the user's usual output through `waveOut`.
- Game pads are not read yet on Windows: keyboard and mouse.
- With Wine installed, the packaging also records the start-up cache by running the game once without a window (in a Wine prefix of its own, `build/wine`: nothing of your Wine is touched); without Wine the package is made without, and starts a little slower.

What was tried is the Windows build under Wine: the same `java.exe`, `user32`, `winmm` and Vulkan calls, answered by Wine. A real Windows has not run it yet; the first run there is the one to report (the line `SILLAGE_VULKAN_OK …` at the start, `SILLAGE_RUN_OK …` at the end).

## FreeBSD

```sh
pkg install openjdk25-jre-headless vulkan-loader mesa-dri libxcb libxkbcommon      # as root, once
```

On the development computer: `./sillage package demo --portable` makes `build/package/demo-portable/` (the game's jars and a launcher, 4 MB, nothing of a processor or a system in it). Copy the folder to the FreeBSD machine and run `./demo` from an X11 or Wayland session.

- Sound goes to `/dev/dsp` (`kldload snd_hda`, or the driver of the sound card, if `/dev/dsp` is missing); `--audio-device /dev/dsp1` picks another, `--mute` none.
- Game pads are not read yet on FreeBSD: keyboard and mouse.
- The graphics card needs its Vulkan driver (Intel and AMD through `drm-kmod` and Mesa; without one, Mesa's software renderer draws, slowly).

## NetBSD

NetBSD 11.1 cannot run the game natively yet: its binary packages stop at OpenJDK 21 (the engine calls the system's libraries through `java.lang.foreign`, final since Java 22), and its packaged Mesa brings no Vulkan driver. It runs it through its **Linux emulation**, with Linux's Java and Linux's software Vulkan, in a window of NetBSD's own X server and with NetBSD's sound.

Once, as root (and `compat_linux` in `/etc/modules.conf`, the procfs line in `/etc/fstab`, to keep them across reboots):

```sh
modload compat_linux
mkdir -p /emul/linux && cd /emul/linux
tar -xzpf /path/to/linux-rootfs.tar.gz                    # made on the development computer: tools/linux-rootfs.sh > linux-rootfs.tar.gz
mkdir -p opt/jdk && tar -xzf /path/to/OpenJDK25U-jdk_x64_linux_hotspot_*.tar.gz -C opt/jdk --strip-components=1    # Temurin 25 for Linux x64
mount_procfs -o linux procfs /emul/linux/proc
```

Then, from an X session, with the portable folder (`./sillage package demo --portable`):

```sh
JAVA_HOME=/emul/linux/opt/jdk ./demo --audio-device /dev/audio --quality low
```

- The picture is drawn by the processor (Mesa's llvmpipe): a Linux graphics driver cannot drive NetBSD's kernel. Measured in a 4-core virtual machine: about 90 ms a frame at 1280 × 800 in `low` quality. It runs; it is not a way to play.
- `--audio-device /dev/audio` is needed: there is no ALSA under the emulation, and NetBSD's kernel translates the OSS calls the engine then makes.
- Keyboard and mouse only.
- When NetBSD's packages carry a Java 22 or later and a Vulkan driver, the same portable folder should start natively (its sound will then need an output of its own: NetBSD's `/dev/audio` is not OSS without the emulation).

## A web page

```sh
./sillage web demo --serve        # builds build/web/demo and shows it at http://127.0.0.1:8080
```

The game is compiled to WebAssembly and drawn with WebGL 2 by the engine's second renderer; `build/web/demo` is a folder of static files to put on any web server. It needs a browser with WebAssembly garbage collection and WebGL 2 with floating-point render targets (the current Chromium, Firefox and Safari). Sound starts at the first key or touch, as browsers require. On a touch screen the engine's touch controls appear by themselves.

A game needs a module `GAME-web` beside it, listed in `project.yaml`: copy `demo-web/` (a `module.yaml`, an `index.html`, a link to the game's `Main.kt` and links to its resource folders). Models, images and sounds are fetched from the page's folder before the game starts, with a count on screen.

## Android

```sh
./kotlin build -m demo-android    # build/tasks/_demo-android_buildAndroidDebug/gradle-project-debug.apk
```

Drawn with OpenGL ES 3 by the same second renderer, played with the touch controls, sound through `AudioTrack`. A game needs a module `GAME-android`, listed in `project.yaml`: copy `demo-android/` and change its `applicationId`. Building it needs the Android SDK on the computer; this is a debug build, to sign with a key of your own before giving it to anyone.

Both were tried with software rendering only (a headless Chromium, the emulator): they draw the right picture, to within a few pixels of Vulkan's; how smooth they are on a real phone or a real browser with its GPU is not measured yet.
