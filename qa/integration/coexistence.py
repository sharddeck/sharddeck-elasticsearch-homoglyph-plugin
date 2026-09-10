#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Verify bundled ICU and the host analysis-icu plugin coexist without resource/class conflicts."""
import argparse, datetime, hashlib, json, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "qa"))
from node import Node
from run import TARGETS

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("targets", nargs="*", default=["es7173", "es8", "es9", "os2", "os3"])
    args = parser.parse_args()
    if any(target not in TARGETS for target in args.targets):
        parser.error("Unknown engine target")
    results = []
    for target in args.targets:
        module, version, image = TARGETS[target]
        artifact = next((ROOT / "adapters" / module / "build" / version / "distributions").glob(f"*-{version}.zip"))
        node = Node("sharddeck-homoglyph-coexist-" + target, image, artifact, icu=True)
        result = {"target": target, "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "image": image}
        print("Checking ICU coexistence: " + target, flush=True)
        try:
            with node:
                for filters, text, expected in [
                    ([{"type": "homoglyph", "profile": "unicode_search_v1"}], "pаypаl", "paypal"),
                    (["icu_normalizer"], "Ａ", "a"),
                    (["icu_normalizer", {"type": "homoglyph", "profile": "unicode_search_v1"}], "héllo", "hello")
                ]:
                    code, body = node.request("/_analyze", {"tokenizer": "keyword", "filter": filters, "text": text}, "POST")
                    if code != 200 or [term["token"] for term in body["tokens"]] != [expected]:
                        raise RuntimeError(str(body))
            result.update(status="passed", oom_killed=node.state["State"]["OOMKilled"])
            if result["oom_killed"]:
                result["status"] = "failed"
        except Exception as error:
            result.update(status="failed", error=str(error))
        results.append(result)
        (ROOT / "qa/integration/coexistence.json").write_text(json.dumps({"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "results": results}, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    return int(any(result["status"] != "passed" for result in results))

if __name__ == "__main__":
    raise SystemExit(main())
