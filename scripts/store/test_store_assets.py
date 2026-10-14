#!/usr/bin/env python3
"""Checks of the store assets committed to this repository: the App Store and
Google Play listing text and screenshots.

Pull requests and main run these (scripts/test.sh, store-automation.yml). A release
run checks only the assets its switches upload (`ios_metadata.py check` and
`play_listing.py check` with --listing / --screenshots), so a listing or screenshot
a release does not upload never blocks it (`scripts/test.sh --release-gate` skips
this file). The release scripts' own tests (test_*_release.py) never read these
assets.

Run: python3 -m unittest scripts/store/test_store_assets.py
"""
from __future__ import annotations

import io
import os
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import ios_metadata  # noqa: E402
import play_listing  # noqa: E402


class AppStoreAssetTests(unittest.TestCase):
    def test_repository_listing_is_valid(self) -> None:
        for locale in ios_metadata.locales():
            with self.subTest(locale=locale):
                values = ios_metadata.load_locale(ios_metadata.LISTING_DIR / locale)
                self.assertIn("description.txt", values)
                self.assertIn("name.txt", values)

    def test_repository_screenshots_are_app_store_ready(self) -> None:
        for spec in ios_metadata.SCREENSHOT_SETS:
            with self.subTest(spec.label):
                self.assertGreaterEqual(len(ios_metadata.screenshot_files(spec)), 1)


class PlayAssetTests(unittest.TestCase):
    def test_repository_listing_and_screenshots_are_valid(self) -> None:
        for locale in play_listing.locales():
            with self.subTest(locale=locale):
                values = play_listing.load_locale(play_listing.LISTING_DIR / locale)
                self.assertEqual(set(values), set(play_listing.FIELDS))
        self.assertGreaterEqual(len(play_listing.screenshot_files()), play_listing.SCREENSHOT_COUNT[0])

    def test_repository_listing_and_screenshots_pass_the_upload_dry_run(self) -> None:
        out = io.StringIO()
        env = {"UPLOAD_LISTING": "true", "UPLOAD_SCREENSHOTS": "true"}
        with mock.patch.dict(os.environ, env), redirect_stdout(out), redirect_stderr(out):
            play_listing.main(["upload", "--dry-run"])
        self.assertIn(f"screenshots={len(play_listing.screenshot_files())}", out.getvalue())


if __name__ == "__main__":
    unittest.main()
