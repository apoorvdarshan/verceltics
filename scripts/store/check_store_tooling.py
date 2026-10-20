#!/usr/bin/env python3
"""Offline smoke check of the installed store tooling (requirements-store.txt).

Release jobs install requirements-store.txt next to the App Store Connect and
Google Play credentials, and a Dependabot pip update changes nothing else, so
store-automation.yml installs it and runs this check before such a change is
merged. With a throwaway key and no network:

  - every package requirements-store.in pins is installed at exactly that version
  - PyJWT signs an App Store Connect token (ES256) through
    asc_release.make_asc_token, and the token verifies with the public key
  - google-auth and google-api-python-client build the Play Developer API client
    through play_listing.build_android_publisher, from the discovery document
    bundled with the library, with every edits() method the release scripts call

Needs `pip install --require-hashes -r requirements-store.txt`. Uses no
credentials and never contacts a store.
"""
from __future__ import annotations

import importlib.metadata
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

PIN = re.compile(r"([A-Za-z0-9][A-Za-z0-9._-]*)(?:\[[^\]]*\])?==([^\s;]+)")
# edits() methods the release scripts call (play_listing.py, android_release.py).
PLAY_EDIT_METHODS = {
    "": ("insert", "commit", "delete"),
    "listings": ("update",),
    "images": ("deleteall", "upload"),
    "bundles": ("list",),
    "apks": ("list",),
    "tracks": ("list", "get"),
}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit(f"error: {message}")


def pinned_versions(path: Path = ROOT / "requirements-store.in") -> dict[str, str]:
    pins = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        match = PIN.fullmatch(line)
        require(match is not None, f"{path.name}: pin each package exactly (name==version), got {line!r}")
        pins[match.group(1)] = match.group(2)
    require(bool(pins), f"{path.name} pins no packages")
    return pins


def check_versions() -> None:
    for name, wanted in pinned_versions().items():
        try:
            installed = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            raise SystemExit(f"error: {name} is not installed; is it in requirements-store.txt?") from None
        require(installed == wanted,
                f"{name} {installed} is installed, but requirements-store.in pins {wanted}; "
                "regenerate requirements-store.txt")
        print(f"  {name}=={installed}")


def pem(private_key) -> str:
    from cryptography.hazmat.primitives import serialization

    return private_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()


def check_app_store_connect_token() -> None:
    import jwt
    from cryptography.hazmat.primitives.asymmetric import ec

    import asc_release

    key = ec.generate_private_key(ec.SECP256R1())
    issuer = "00000000-0000-0000-0000-000000000000"
    token = asc_release.make_asc_token("SMOKETEST1", issuer, pem(key))
    header = jwt.get_unverified_header(token)
    require(header.get("alg") == "ES256" and header.get("kid") == "SMOKETEST1",
            f"unexpected App Store Connect token header {header}")
    claims = jwt.decode(token, key.public_key(), algorithms=["ES256"], audience="appstoreconnect-v1",
                        issuer=issuer)
    require(0 < claims["exp"] - claims["iat"] <= 1200,
            "App Store Connect tokens must expire within 20 minutes")
    print("  App Store Connect token: ES256, verified with the public key")


def check_play_client() -> None:
    from cryptography.hazmat.primitives.asymmetric import rsa
    from googleapiclient.http import MediaFileUpload

    import play_listing

    require(callable(MediaFileUpload), "googleapiclient.http has no MediaFileUpload (Play screenshot uploads)")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    account = {
        "type": "service_account",
        "project_id": "store-tooling-check",
        "private_key_id": "0" * 40,
        "private_key": pem(key),
        "client_email": "store-tooling-check@store-tooling-check.iam.gserviceaccount.com",
        "client_id": "0",
        "token_uri": "https://oauth2.googleapis.com/token",
    }
    edits = play_listing.build_android_publisher(account).edits()
    for resource, methods in PLAY_EDIT_METHODS.items():
        target = getattr(edits, resource)() if resource else edits
        for method in methods:
            where = f"edits().{resource + '().' if resource else ''}{method}"
            require(callable(getattr(target, method, None)), f"the Play client has no {where}")
    print("  Google Play client: androidpublisher v3 built offline, with every edits() method used")


def main() -> None:
    print("store tooling (requirements-store.in pins):")
    check_versions()
    check_app_store_connect_token()
    check_play_client()
    print("store tooling OK")


if __name__ == "__main__":
    main()
