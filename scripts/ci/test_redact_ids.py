#!/usr/bin/env python3
"""Offline tests for redact_ids.py (failure logs of the public iOS release workflow).

Run: python3 -m unittest scripts/ci/test_redact_ids.py
"""
from __future__ import annotations

import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import redact_ids  # noqa: E402

KEY = "TESTKEY123"
ISSUER = "00000000-0000-4000-8000-00000000abcd"
ENV = {"ASC_KEY_ID": KEY, "ASC_ISSUER_ID": ISSUER}


def run(root: Path, env: dict[str, str] = ENV) -> tuple[int, str]:
    out = io.StringIO()
    with mock.patch.dict(os.environ, env, clear=True), redirect_stdout(out), redirect_stderr(out):
        code = redact_ids.main(["redact_ids.py", str(root)])
    return code, out.getvalue()


class RedactTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "logs"
        self.root.mkdir()

    def test_text_logs_keep_their_content_without_the_ids(self) -> None:
        log = self.root / "upload.log"
        log.write_text(f"-authenticationKeyID {KEY} -authenticationKeyIssuerID {ISSUER.upper()}\nAuthKey_{KEY}.p8\n"
                       f"İstanbul ß {KEY.lower()}{KEY}\n", encoding="utf-8")
        code, output = run(self.root)
        self.assertEqual(code, 0, output)
        self.assertEqual(
            log.read_text(encoding="utf-8"),
            "-authenticationKeyID <ASC_KEY_ID> -authenticationKeyIssuerID <ASC_ISSUER_ID>\nAuthKey_<ASC_KEY_ID>.p8\n"
            "İstanbul ß <ASC_KEY_ID><ASC_KEY_ID>\n",
        )
        self.assertNotIn(KEY, output)
        self.assertNotIn(KEY.lower(), output.lower())

    def test_binary_and_utf16_files_holding_an_id_are_removed(self) -> None:
        nested = self.root / "Verceltics.xcdistributionlogs"
        nested.mkdir()
        (nested / "state.plist").write_bytes(b"bplist00\x00\x01" + KEY.encode() + b"\x00")
        (nested / "notes.txt").write_bytes(ISSUER.encode("utf-16-le"))
        (nested / "other.txt").write_bytes(f"key {KEY.lower()}".encode("utf-16-be"))
        (nested / "clean.bin").write_bytes(b"\x00\x01binary without ids")
        code, output = run(self.root)
        self.assertEqual(code, 0, output)
        self.assertEqual(sorted(p.name for p in nested.iterdir()), ["clean.bin"])

    def test_names_holding_an_id_are_renamed_and_links_removed(self) -> None:
        folder = self.root / f"keys-{KEY}"
        folder.mkdir()
        (folder / f"AuthKey_{KEY}.txt").write_text("nothing secret\n")
        outside = Path(self.tmp.name) / "outside.txt"
        outside.write_text(f"{KEY}\n")
        (self.root / "link.txt").symlink_to(outside)
        code, output = run(self.root)
        self.assertEqual(code, 0, output)
        self.assertEqual([p.relative_to(self.root).as_posix() for p in sorted(self.root.rglob("*"))],
                         ["keys-<ASC_KEY_ID>", "keys-<ASC_KEY_ID>/AuthKey_<ASC_KEY_ID>.txt"])
        self.assertEqual(outside.read_text(), f"{KEY}\n", "files outside the folder are never touched")
        self.assertNotIn(KEY, output, "messages only show redacted names")

    def test_nothing_to_do_without_ids_or_matches(self) -> None:
        log = self.root / "archive.log"
        log.write_text("** ARCHIVE SUCCEEDED **\n")
        self.assertEqual(run(self.root, {})[0], 0)
        self.assertEqual(run(self.root)[0], 0)
        self.assertEqual(log.read_text(), "** ARCHIVE SUCCEEDED **\n")
        self.assertEqual(run(Path(self.tmp.name) / "missing")[0], 0)

    def test_an_id_that_survives_fails_the_step(self) -> None:
        (self.root / f"upload-{KEY}.log").write_text(f"{KEY}\n")
        with mock.patch.object(redact_ids, "redact", return_value=[]):
            code, output = run(self.root)
        self.assertEqual(code, 1)
        self.assertIn("upload-<ASC_KEY_ID>.log", output)
        self.assertIn("not uploading", output)
        self.assertNotIn(KEY, output)


if __name__ == "__main__":
    unittest.main()
