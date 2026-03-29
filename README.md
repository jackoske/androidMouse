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
## Running as a systemd service

Instead of running the server manually, you can install it as a systemd user service:

```bash
# Copy the unit file
mkdir -p ~/.config/systemd/user
cp server/androidmouse.service ~/.config/systemd/user/

# Edit the ExecStart path if your repo is in a different location
# Default assumes ~/Documents/code/androidMouse/

# Enable and start
systemctl --user daemon-reload
systemctl --user enable --now androidmouse

# Check status / logs
systemctl --user status androidmouse
journalctl --user -u androidmouse -f
```

## Status

Nearly ready to ship. Core functionality (mouse, keyboard, scrolling, clicks) is working. Remaining work is polish: QR code fix, tap-to-click gesture.
