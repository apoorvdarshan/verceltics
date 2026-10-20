#!/usr/bin/env python3
"""Checks of the store assets committed to this repository: the App Store and
Google Play listing text and screenshots, and the App Store header and search
results images.

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

import asc_release  # noqa: E402
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
        # iPhone and iPad, plus iPhone Duo and Apple Watch when their folders hold frames.
        sets = ios_metadata.screenshot_sets()
        self.assertEqual([spec.display_type for spec, _ in sets][:2],
                         [spec.display_type for spec in ios_metadata.SCREENSHOT_SETS])
        for spec, files in sets:
            with self.subTest(spec.label):
                self.assertGreaterEqual(len(files), 1)

    def test_repository_creative_images_pass_the_local_checks(self) -> None:
        # What IOS_UPLOAD_CREATIVE_ASSETS would upload: at most one image per slot, of a
        # size and format Apple takes, without alpha, well formed. A slot may be empty,
        # but both slot folders stay committed (each holds its README).
        for folder, placement_type, label, formats in asc_release.CREATIVE_SLOTS:
            slot = asc_release.DEFAULT_CREATIVE_DIR / folder
            with self.subTest(slot=folder):
                self.assertTrue(slot.is_dir(), f"{slot} is missing")
                images = [p for p in slot.iterdir() if p.is_file() and p.suffix.lower() in asc_release.CREATIVE_EXTENSIONS]
                self.assertLessEqual(len(images), 1, f"the {label} slot takes one image")
                for path in images:
                    self.assertIsNone(asc_release.creative_problem(placement_type, path, formats), path.name)


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
