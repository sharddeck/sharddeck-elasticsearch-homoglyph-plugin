#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Summarize JMH timings and allocation, retaining environment and confidence intervals."""
import argparse, json
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    environment = json.loads((args.results / "environment.json").read_text())
    if len(environment["runs"]) != 4 or any(run["exit_code"] for run in environment["runs"]):
        parser.error("Benchmark runner did not complete successfully")
    rows = []
    for run in environment["runs"]:
        for result in json.loads((args.results / (run["name"] + ".json")).read_text()):
            metric = result["primaryMetric"]
            row = {"benchmark": result["benchmark"], "parameters": result.get("params", {}),
                   "score": metric["score"], "unit": metric["scoreUnit"],
                   "confidence_interval": metric["scoreConfidence"],
                   "allocated_bytes_per_operation": result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")}
            if run["name"] == "multilingual":
                row["documents_per_second"] = environment["corpus"]["document_count"] * 1e6 / metric["score"]
            rows.append(row)
    report = {"environment": environment, "status": "harness_smoke" if environment["quick"] else "development_measurements",
              "release_performance_certified": False, "measurements": rows}
    (args.results / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Summarized {len(rows)} measurements in {args.results / 'summary.json'}")

if __name__ == "__main__":
    main()
