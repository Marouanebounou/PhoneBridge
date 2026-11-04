from __future__ import annotations

import json
import secrets
import socket
import sys
import time
from pathlib import Path
from typing import Any

PORT = 47860
APP_DIRECTORY = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
PAIRING_FILE = APP_DIRECTORY / "pairing_code.txt"
BUTTONS = {
    "A": "XUSB_GAMEPAD_A",
    "B": "XUSB_GAMEPAD_B",
    "X": "XUSB_GAMEPAD_X",
    "Y": "XUSB_GAMEPAD_Y",
    "LB": "XUSB_GAMEPAD_LEFT_SHOULDER",
    "RB": "XUSB_GAMEPAD_RIGHT_SHOULDER",
    "BACK": "XUSB_GAMEPAD_BACK",
    "START": "XUSB_GAMEPAD_START",
    "LS": "XUSB_GAMEPAD_LEFT_THUMB",
    "RS": "XUSB_GAMEPAD_RIGHT_THUMB",
    "DPAD_UP": "XUSB_GAMEPAD_DPAD_UP",
    "DPAD_DOWN": "XUSB_GAMEPAD_DPAD_DOWN",
    "DPAD_LEFT": "XUSB_GAMEPAD_DPAD_LEFT",
    "DPAD_RIGHT": "XUSB_GAMEPAD_DPAD_RIGHT",
}


def local_addresses() -> list[str]:
    found: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET, socket.SOCK_DGRAM):
            ip = item[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                found.add(ip)
    except OSError:
        pass
    return sorted(found)


def persistent_pairing_code() -> str:
    try:
        saved = PAIRING_FILE.read_text(encoding="utf-8").strip()
        if saved.isdigit() and len(saved) == 6:
            return saved
    except OSError:
        pass
    value = f"{secrets.randbelow(1_000_000):06d}"
    PAIRING_FILE.write_text(value + "\n", encoding="utf-8")
    return value


def decode_message(packet: bytes) -> dict[str, Any] | None:
    try:
        value = json.loads(packet.decode("utf-8"))
        return value if isinstance(value, dict) else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


def apply_input(gamepad: Any, message: dict[str, Any], vg: Any) -> None:
    all_buttons = set(message.get("buttons", []))
    wanted = all_buttons.intersection(BUTTONS)
    previous = getattr(gamepad, "_phonebridge_buttons", set())
    for name in previous - wanted:
        gamepad.release_button(button=getattr(vg.XUSB_BUTTON, BUTTONS[name]))
    for name in wanted - previous:
        gamepad.press_button(button=getattr(vg.XUSB_BUTTON, BUTTONS[name]))
    gamepad._phonebridge_buttons = wanted

    axes = message.get("axes") or {}
    def axis(name: str) -> float:
        try:
            return max(-1.0, min(1.0, float(axes.get(name, 0.0))))
        except (TypeError, ValueError):
            return 0.0

    gamepad.left_joystick_float(x_value_float=axis("lx"), y_value_float=-axis("ly"))
    gamepad.right_joystick_float(x_value_float=axis("rx"), y_value_float=-axis("ry"))
    left_trigger = 1.0 if "LT_BUTTON" in all_buttons else axis("lt")
    right_trigger = 1.0 if "RT_BUTTON" in all_buttons else axis("rt")
    gamepad.left_trigger_float(value_float=max(0.0, min(1.0, left_trigger)))
    gamepad.right_trigger_float(value_float=max(0.0, min(1.0, right_trigger)))
    gamepad.update()


def main() -> int:
    try:
        import vgamepad as vg
    except Exception as error:
        print(f"Could not connect to the ViGEmBus virtual gamepad driver: {error}")
        print("Install or repair ViGEmBus, restart Windows, then run PhoneBridgeReceiver.exe again.")
        return 1

    pairing_code = persistent_pairing_code()
    token = secrets.token_urlsafe(24)
    paired_ip: str | None = None
    gamepad = None
    last_packet_at = 0.0
    last_heartbeat = 0.0
    last_count_print = 0.0
    packet_count = 0

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("0.0.0.0", PORT))
            server.settimeout(0.1)
            print("PhoneBridge PC receiver")
            print("-----------------------")
            addresses = local_addresses()
            print("PC address(es):")
            for address in addresses:
                print(f"  {address}")
            if not addresses:
                print("  Could not determine a local IPv4 address; use ipconfig to find it.")
            print(f"Pairing code: {pairing_code}")
            print(f"Listening on UDP port {PORT}. Keep this window open.")
            print("Allow Python through Windows Firewall on Private networks if prompted.")
            print("Press Ctrl+C to stop.\n")
            while True:
                now = time.monotonic()
                try:
                    payload, address = server.recvfrom(4096)
                    message = decode_message(payload)
                    if not message:
                        continue
                    kind = message.get("type")
                    if kind == "pair":
                        if str(message.get("code", "")) != pairing_code:
                            server.sendto(json.dumps({"type": "error", "message": "Pairing code does not match."}).encode(), address)
                            continue
                        if paired_ip is None or paired_ip == address[0]:
                            paired_ip = address[0]
                            if gamepad is None:
                                gamepad = vg.VX360Gamepad()
                            server.sendto(json.dumps({"type": "paired", "token": token}).encode(), address)
                            print(f"Phone paired from {address[0]}.")
                        continue

                    if kind != "input" or gamepad is None or address[0] != paired_ip or message.get("token") != token:
                        continue

                    apply_input(gamepad, message, vg)
                    last_packet_at = now
                    packet_count += 1
                    if now - last_count_print >= 2.0:
                        rate = packet_count / max(0.001, now - last_count_print)
                        print(f"Receiving controller input from {paired_ip} ({rate:.0f} packets/s).")
                        packet_count = 0
                        last_count_print = now
                except socket.timeout:
                    pass

                if gamepad is not None and paired_ip is not None and now - last_packet_at > 1.0 and now - last_heartbeat > 1.0:
                    gamepad.reset()
                    gamepad.update()
                    last_heartbeat = now
    except KeyboardInterrupt:
        print("\nStopping PhoneBridge.")
    except Exception as error:
        print(f"Receiver error: {error}")
        return 1
    finally:
        if gamepad is not None:
            try:
                gamepad.reset()
                gamepad.update()
            except Exception:
                pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
