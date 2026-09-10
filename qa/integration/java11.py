#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Exercise the ES7.17.3 baseline with a real Java 11 runtime as well as its bundled JDK."""
import datetime, hashlib, json, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "qa"))
from node import Node

artifact = ROOT / "adapters/elasticsearch-7/build/7.17.3/distributions/sharddeck-homoglyph-elasticsearch-7-0.1.0-SNAPSHOT-7.17.3.zip"
node = Node("sharddeck-homoglyph-java11", "docker.elastic.co/elasticsearch/elasticsearch:7.17.3", artifact, java11=True)
report = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}
with node:
    code, info = node.request("/_nodes/_local/jvm")
    assert code == 200
    report["java_version"] = next(iter(info["nodes"].values()))["jvm"]["version"]
    assert report["java_version"].startswith("11.")
    for config, text, expected in [
        ({"type": "homoglyph"}, "tℯst😀", ["test😀"]),
        ({"type": "homoglyph"}, "о", ["o"]),
        ({"type": "homoglyph", "profile": "unicode_expand_v1"}, "é", ["é", "é"]),
        ({"type": "homoglyph", "profile": "unicode_search_v1"}, "he\u0301llo", ["hello"])
    ]:
        code, body = node.request("/_analyze", {"tokenizer": "keyword", "filter": [config], "text": text}, "POST")
        assert code == 200 and [term["token"] for term in body["tokens"]] == expected, body
report.update(image_id=node.state["Image"], oom_killed=node.state["State"]["OOMKilled"], exit_code=node.state["State"]["ExitCode"])
report["status"] = "passed" if not report["oom_killed"] and report["exit_code"] in (0, 143) else "failed"
(ROOT / "qa/integration/java11.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report), flush=True)
raise SystemExit(report["status"] != "passed")
