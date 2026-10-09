#!/usr/bin/env python3
"""Report Android switches; Play uploads require the master switch and a testing track."""
import argparse
import os
from pathlib import Path

TRUTHY = {"1", "true", "yes", "on"}

# Repository variable value -> Google Play track id. Closed testing is Play's "alpha" track.
PLAY_TRACKS = {"internal": "internal", "closed": "alpha"}


def play_track(value: str | None) -> str | None:
    """The Play track a release goes to, or None when uploads stay off."""
    choice = (value or "").strip().lower()
    if choice in {"", "none", "off", "false"}:
        return None
    if choice == "production":
        raise ValueError(
            "STORE_PLAY_TRACK=production is not supported: Verceltics ships only to internal or "
            "closed testing until Google allows a production release"
        )
    if choice not in PLAY_TRACKS:
        raise ValueError(f"STORE_PLAY_TRACK must be none, internal, or closed (got {value!r})")
    return PLAY_TRACKS[choice]


def effective_gates(env: dict[str, str]) -> dict[str, str]:
    # Match the workflow's exact string comparison. Unset or other values are off.
    master = env.get("STORE_ANDROID_RELEASE_ENABLED") == "true"
    track = play_track(env.get("STORE_PLAY_TRACK"))
    whats_new = env.get("STORE_UPLOAD_WHATS_NEW", "").strip().lower() in TRUTHY
    upload = master and track is not None
    return {
        "android_release_enabled": str(master).lower(),
        "play_upload": str(upload).lower(),
        "play_track": track if upload else "",
        "upload_whats_new": str(upload and whats_new).lower(),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        gates = effective_gates(dict(os.environ))
    except ValueError as exc:
        parser.error(str(exc))
    for name in ("STORE_ANDROID_RELEASE_ENABLED", "STORE_PLAY_TRACK", "STORE_UPLOAD_WHATS_NEW"):
        print(f"{name}: configured={os.environ.get(name, '') or 'unset'}")
    for output, value in gates.items():
        print(f"effective {output}={value or 'none'}")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            handle.writelines(f"{output}={value}\n" for output, value in gates.items())
