#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Validate every homoglyph filter in an index JSON file using the production Java parser."""
import argparse, json, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key: " + key)
        result[key] = value
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    if args.file.stat().st_size > 1024 * 1024:
        parser.error("Configuration must fit in 1 MiB")
    data = json.loads(args.file.read_text(), object_pairs_hook=unique_pairs)
    filters = []
    def walk(value):
        if isinstance(value, dict):
            if value.get("type") == "homoglyph":
                filters.append(value)
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(data)
    if not filters:
        parser.error("No filter with type=homoglyph found")
    for settings in filters:
        if any(not isinstance(v, (str, bool, int)) for v in settings.values()):
            parser.error("Homoglyph settings must be strings, booleans or integers")
        arguments = [key + "=" + (str(value).lower() if isinstance(value, bool) else str(value)) for key, value in settings.items()]
        subprocess.run(["java", "-Xmx128m", "-jar", str(ROOT / "tools/build/libs/homoglyph-tools.jar"), "validate", *arguments], check=True)
    expected = data.get("mappings", {}).get("_meta", {}).get("homoglyph_profile_fingerprints", {})
    if expected:
        manifest = json.loads(subprocess.check_output(["java", "-Xmx128m", "-jar", str(ROOT / "tools/build/libs/homoglyph-tools.jar"), "profiles"], text=True))
        installed = {profile["id"]: profile["fingerprint_sha256"] for profile in manifest["profiles"]}
        for profile, fingerprint in expected.items():
            if installed.get(profile) != fingerprint:
                parser.error("Profile fingerprint mismatch: " + profile)
    print(f"Validated {len(filters)} filter definitions")

if __name__ == "__main__":
    main()
