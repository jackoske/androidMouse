# Android Mouse

Turn your Android phone into a wireless mouse and keyboard for your Linux desktop.

The phone connects over Wi-Fi to a lightweight Python daemon (`server/mouse_daemon.py`) that injects input events via uinput. Supports mouse movement, scrolling, left/middle/right click, and full keyboard input.

## Architecture

- **Android app** (Kotlin) — touchpad surface, click buttons, keyboard input. Sends HID-style reports over TCP.
- **Linux daemon** (`server/mouse_daemon.py`) — receives reports and writes them to `/dev/uinput` via python-evdev.
- **mDNS discovery** — optional auto-discovery so the app can find the server without manual IP entry (requires `zeroconf` pip package).

## Server setup

```bash
# Install dependencies
sudo pacman -S python-evdev    # Arch
pip install zeroconf            # optional: mDNS auto-discovery
pip install qrcode              # optional: terminal QR code

# Allow uinput access (or just use sudo)
sudo usermod -aG input $USER
# log out and back in

# Run
python server/mouse_daemon.py [port]   # default: 9393
```

## Building the app

Standard Android/Gradle build:

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Known issues

- **QR code not working** — the terminal QR code feature for quick connection is currently broken. Use manual IP entry for now.
- **Server must be run manually** — there's no systemd service or daemon integration yet. For production use this should be daemonized (systemd unit, etc.) rather than run in a foreground terminal.

## Status

Nearly ready to ship. Core functionality (mouse, keyboard, scrolling, clicks) is working. Remaining work is polish: QR code fix, daemon integration, sensitivity settings UI, tap-to-click gesture.
