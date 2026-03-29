#!/usr/bin/env python3
"""
androidmouse-daemon
Receives mouse/keyboard reports from the Android app over TCP and injects
them into the Linux input subsystem via uinput.

Protocol (each packet):
  Mouse    (5 bytes): [0x01][buttons:u8][dx:i8][dy:i8][wheel:i8]
  Keyboard (9 bytes): [0x02][mods:u8][reserved:u8][key0..key5:u8]

Usage:
  python mouse_daemon.py [port]   (default port: 9393)

Requirements:
  sudo pacman -S python-evdev
  pip install qrcode          (optional, for terminal QR codes)
  sudo usermod -aG input $USER   (then log out/in, or use sudo for now)
"""

import socket
import struct
import sys
import signal
import shutil

try:
    from evdev import UInput, ecodes as e
except ImportError:
    sys.exit("Missing dependency — run:  sudo pacman -S python-evdev")

try:
    import qrcode
    HAS_QR = True
except ImportError:
    HAS_QR = False

try:
    from zeroconf import ServiceInfo, Zeroconf
    import ipaddress
    HAS_ZEROCONF = True
except ImportError:
    HAS_ZEROCONF = False

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9393

# ── HID keycode → Linux evdev keycode ────────────────────────────────────────

HID_TO_KEY: dict[int, int] = {
    0x04: e.KEY_A,      0x05: e.KEY_B,     0x06: e.KEY_C,     0x07: e.KEY_D,
    0x08: e.KEY_E,      0x09: e.KEY_F,     0x0A: e.KEY_G,     0x0B: e.KEY_H,
    0x0C: e.KEY_I,      0x0D: e.KEY_J,     0x0E: e.KEY_K,     0x0F: e.KEY_L,
    0x10: e.KEY_M,      0x11: e.KEY_N,     0x12: e.KEY_O,     0x13: e.KEY_P,
    0x14: e.KEY_Q,      0x15: e.KEY_R,     0x16: e.KEY_S,     0x17: e.KEY_T,
    0x18: e.KEY_U,      0x19: e.KEY_V,     0x1A: e.KEY_W,     0x1B: e.KEY_X,
    0x1C: e.KEY_Y,      0x1D: e.KEY_Z,
    0x1E: e.KEY_1,      0x1F: e.KEY_2,     0x20: e.KEY_3,     0x21: e.KEY_4,
    0x22: e.KEY_5,      0x23: e.KEY_6,     0x24: e.KEY_7,     0x25: e.KEY_8,
    0x26: e.KEY_9,      0x27: e.KEY_0,
    0x28: e.KEY_ENTER,  0x29: e.KEY_ESC,   0x2A: e.KEY_BACKSPACE, 0x2B: e.KEY_TAB,
    0x2C: e.KEY_SPACE,  0x2D: e.KEY_MINUS, 0x2E: e.KEY_EQUAL,
    0x2F: e.KEY_LEFTBRACE,  0x30: e.KEY_RIGHTBRACE, 0x31: e.KEY_BACKSLASH,
    0x33: e.KEY_SEMICOLON,  0x34: e.KEY_APOSTROPHE, 0x35: e.KEY_GRAVE,
    0x36: e.KEY_COMMA,  0x37: e.KEY_DOT,   0x38: e.KEY_SLASH,
    0x39: e.KEY_CAPSLOCK,
    0x3A: e.KEY_F1,  0x3B: e.KEY_F2,  0x3C: e.KEY_F3,  0x3D: e.KEY_F4,
    0x3E: e.KEY_F5,  0x3F: e.KEY_F6,  0x40: e.KEY_F7,  0x41: e.KEY_F8,
    0x42: e.KEY_F9,  0x43: e.KEY_F10, 0x44: e.KEY_F11, 0x45: e.KEY_F12,
    0x4A: e.KEY_HOME,   0x4B: e.KEY_PAGEUP,  0x4C: e.KEY_DELETE,
    0x4D: e.KEY_END,    0x4E: e.KEY_PAGEDOWN,
    0x4F: e.KEY_RIGHT,  0x50: e.KEY_LEFT, 0x51: e.KEY_DOWN, 0x52: e.KEY_UP,
}

# Modifier bitmask position → evdev key (LSB first)
MOD_KEYS = [
    e.KEY_LEFTCTRL,  e.KEY_LEFTSHIFT,  e.KEY_LEFTALT,  e.KEY_LEFTMETA,
    e.KEY_RIGHTCTRL, e.KEY_RIGHTSHIFT, e.KEY_RIGHTALT, e.KEY_RIGHTMETA,
]

CAPABILITIES = {
    e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL],
    e.EV_KEY: [
        e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE,
        *MOD_KEYS,
        *HID_TO_KEY.values(),
    ],
}

# ── Input event helpers ───────────────────────────────────────────────────────

prev_buttons = 0
prev_mods    = 0
prev_keys: set[int] = set()

def handle_mouse(ui: UInput, payload: bytes) -> None:
    global prev_buttons
    buttons, dx, dy, wheel = struct.unpack("Bbbb", payload)

    for mask, btn in [(0x01, e.BTN_LEFT), (0x02, e.BTN_RIGHT), (0x04, e.BTN_MIDDLE)]:
        was = bool(prev_buttons & mask)
        now = bool(buttons    & mask)
        if was != now:
            ui.write(e.EV_KEY, btn, int(now))
    prev_buttons = buttons

    if dx:    ui.write(e.EV_REL, e.REL_X,     dx)
    if dy:    ui.write(e.EV_REL, e.REL_Y,     dy)
    if wheel: ui.write(e.EV_REL, e.REL_WHEEL, wheel)
    ui.syn()

def handle_keyboard(ui: UInput, payload: bytes) -> None:
    global prev_mods, prev_keys
    mods     = payload[0]
    keycodes = {k for k in payload[2:8] if k != 0}

    # Modifier changes
    for i, mod_key in enumerate(MOD_KEYS):
        was = bool(prev_mods & (1 << i))
        now = bool(mods      & (1 << i))
        if was != now:
            ui.write(e.EV_KEY, mod_key, int(now))
    prev_mods = mods

    # Regular key changes (released first, then pressed)
    for hid in prev_keys - keycodes:
        evkey = HID_TO_KEY.get(hid)
        if evkey is not None:
            ui.write(e.EV_KEY, evkey, 0)
    for hid in keycodes - prev_keys:
        evkey = HID_TO_KEY.get(hid)
        if evkey is not None:
            ui.write(e.EV_KEY, evkey, 1)
    prev_keys = keycodes
    ui.syn()

# ── TCP server ────────────────────────────────────────────────────────────────

def recv_exact(conn: socket.socket, n: int) -> bytes | None:
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)

def release_all(ui: UInput) -> None:
    """Release all buttons, modifiers, and keys on the server side."""
    global prev_buttons, prev_mods, prev_keys
    # Release mouse buttons
    for mask, btn in [(0x01, e.BTN_LEFT), (0x02, e.BTN_RIGHT), (0x04, e.BTN_MIDDLE)]:
        if prev_buttons & mask:
            ui.write(e.EV_KEY, btn, 0)
    prev_buttons = 0
    # Release modifier keys
    for i, mod_key in enumerate(MOD_KEYS):
        if prev_mods & (1 << i):
            ui.write(e.EV_KEY, mod_key, 0)
    prev_mods = 0
    # Release regular keys
    for hid in prev_keys:
        evkey = HID_TO_KEY.get(hid)
        if evkey is not None:
            ui.write(e.EV_KEY, evkey, 0)
    prev_keys = set()
    ui.syn()

def handle_client(conn: socket.socket, addr: tuple, ui: UInput) -> None:
    print(f"  \033[32m●\033[0m connected from {addr[0]}")
    try:
        while True:
            hdr = recv_exact(conn, 1)
            if hdr is None:
                break
            report_type = hdr[0]
            if report_type == 0x01:
                payload = recv_exact(conn, 4)
                if payload:
                    handle_mouse(ui, payload)
            elif report_type == 0x02:
                payload = recv_exact(conn, 8)
                if payload:
                    handle_keyboard(ui, payload)
    except OSError:
        pass
    finally:
        release_all(ui)
        conn.close()
        print(f"  \033[31m●\033[0m disconnected (all keys released)")

def local_ips() -> list[str]:
    """Return all non-loopback IPv4 addresses on this machine."""
    ips = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for _, _, _, _, addr in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = addr[0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips or ["(unknown — run `ip addr`)"]


def print_banner(ips: list[str], port: int, uinput_path: str) -> None:
    """Print a styled startup banner with optional QR code."""
    CYAN   = "\033[36m"
    BOLD   = "\033[1m"
    DIM    = "\033[2m"
    RESET  = "\033[0m"
    GREEN  = "\033[32m"

    term_width = shutil.get_terminal_size((80, 24)).columns

    print()
    print(f"  {CYAN}{BOLD}Android Mouse{RESET}  {DIM}v1.0{RESET}")
    print(f"  {DIM}{'─' * min(40, term_width - 4)}{RESET}")
    print(f"  {DIM}port{RESET}    {port}")
    print(f"  {DIM}uinput{RESET}  {uinput_path}")
    print()

    primary_ip = ips[0] if ips else "?.?.?.?"
    connect_str = f"{primary_ip}:{port}"

    if HAS_QR:
        # Generate terminal QR code
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=1,
            border=1,
        )
        qr.add_data(connect_str)
        qr.make(fit=True)

        # Render using Unicode half-blocks for compact display
        matrix = qr.get_matrix()
        rows = len(matrix)
        print(f"  {DIM}Scan to connect:{RESET}")
        print()
        for y in range(0, rows, 2):
            line = "    "
            for x in range(len(matrix[0])):
                top = matrix[y][x]
                bot = matrix[y + 1][x] if y + 1 < rows else False
                if top and bot:
                    line += "█"
                elif top:
                    line += "▀"
                elif bot:
                    line += "▄"
                else:
                    line += " "
            print(line)
        print()

    for ip in ips:
        print(f"  {GREEN}●{RESET} {BOLD}{ip}:{port}{RESET}")

    print()
    print(f"  {DIM}Ctrl-C to stop{RESET}")
    print()


def start_mdns(ips: list[str], port: int) -> Zeroconf | None:
    """Advertise the server via mDNS so the Android app can auto-discover it."""
    if not HAS_ZEROCONF:
        return None
    try:
        zc = Zeroconf()
        hostname = socket.gethostname()
        addresses = []
        for ip in ips:
            try:
                addresses.append(ipaddress.ip_address(ip).packed)
            except ValueError:
                pass
        info = ServiceInfo(
            "_androidmouse._tcp.local.",
            f"AndroidMouse ({hostname})._androidmouse._tcp.local.",
            addresses=addresses,
            port=port,
            properties={"version": "1"},
            server=f"{hostname}.local.",
        )
        zc.register_service(info)
        return zc
    except Exception as ex:
        print(f"  mDNS registration failed: {ex}")
        return None

def serve(ui: UInput) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(1)

    ips = local_ips()
    print_banner(ips, PORT, ui.device.path)

    # Advertise via mDNS
    zc = start_mdns(ips, PORT)
    if zc:
        print(f"  \033[36m●\033[0m mDNS: advertising as _androidmouse._tcp")
    else:
        print(f"  \033[2m  mDNS: unavailable (pip install zeroconf)\033[0m")
    print()

    def cleanup(*_):
        if zc:
            zc.unregister_all_services()
            zc.close()
        srv.close()
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)

    while True:
        conn, addr = srv.accept()
        handle_client(conn, addr, ui)

# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    try:
        with UInput(CAPABILITIES, name="AndroidMouse", version=0x3) as ui:
            serve(ui)
    except PermissionError:
        sys.exit(
            "Permission denied opening /dev/uinput.\n"
            "Either run with sudo, or:\n"
            "  sudo usermod -aG input $USER\n"
            "  (log out and back in, then retry)"
        )
