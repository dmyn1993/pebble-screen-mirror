#!/usr/bin/env python3
"""Send a synthetic frame to the Screen Mirror watchapp in the Pebble emulator.

Run this from WSL/Linux where `pebble` and the SDK are available. It first sends
a CONFIG (200x228, viewport mode), then a full keyframe.

Example:
    python tools/send_test_frame.py --pebble /opt/pebble-venv/bin/pebble
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UUID = "be518248-51cb-4b4f-aa58-8c4718411da5"


def run(pebble: str, args: list[str]) -> None:
    command = [pebble, "send-app-message", "--emulator", "emery", "--vnc",
               "--app-uuid", UUID, *args]
    print("+", " ".join(command))
    subprocess.run(command, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pebble", default="pebble")
    parser.add_argument("--frame", default=str(ROOT / "watchapp" / "test_frame.bin"))
    parser.add_argument("--preview", default=str(ROOT / "watchapp" / "test_frame_preview.png"))
    args = parser.parse_args()

    # Generate the payload with the same packer used by the unit tests.
    subprocess.run([
        sys.executable,
        str(ROOT / "tools" / "generate_test_frame.py"),
        "--out", args.frame,
        "--preview", args.preview,
    ], check=True)

    # CONFIG: 200x228, max offset 216, viewport mode, mirroring on.
    run(args.pebble, ["--uint", "1=1", "11=200", "12=228", "13=216", "14=0", "6=1",
                      "--string", "15=Mirroring"])
    # Keyframe 1, 725 tiles, final+keyframe.
    run(args.pebble, ["--uint", "1=2", "3=1", "5=725", "6=3",
                      "--bytes-file", f"7={args.frame}"])


if __name__ == "__main__":
    main()
