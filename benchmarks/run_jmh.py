#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Freeze the executable benchmark JAR and record reproducible JMH JSON plus environment."""
import argparse, datetime, hashlib, json, platform, shutil, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quick", action="store_true", help="Harness smoke only, not release evidence")
    parser.add_argument("--output", type=Path, default=ROOT / "benchmarks/results/latest")
    parser.add_argument("--note", default="Environment isolation not asserted by harness")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / "benchmarks/jmh/build/libs/benchmarks.jar"
    frozen = output / "benchmarks.jar"
    shutil.copyfile(source, frozen)
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True)
    metadata = {
        "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "jar_sha256": hashlib.sha256(frozen.read_bytes()).hexdigest(),
        "corpus": json.loads((ROOT / "benchmarks/corpus/manifest.json").read_text()),
        "platform": platform.platform(), "processor": platform.processor(),
        "java": java.stderr, "note": args.note, "quick": args.quick, "runs": []
    }
    for name, pattern, params in [
        ("tokens", "HomoglyphBenchmark", []),
        ("multilingual", "MultilingualBenchmark.documents", []),
        ("expansion", "ExpansionBenchmark", []),
        ("initialization", "InitializationBenchmark", [])
    ]:
        command = ["java", "-Xms512m", "-Xmx512m", "-jar", str(frozen), pattern,
                   "-f", "1" if args.quick else "3", "-wi", "0" if name == "initialization" else "1" if args.quick else "5",
                   "-i", "1" if name == "initialization" else "2" if args.quick else "10", "-w", "200ms" if args.quick else "2s",
                   "-r", "200ms" if args.quick else "2s", "-prof", "gc", "-foe", "true",
                   "-rf", "json", "-rff", str(output / (name + ".json")), *params]
        print("Running " + name, flush=True)
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        metadata["runs"].append({"name": name, "command": command, "exit_code": result.returncode})
        (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
        if result.returncode:
            return result.returncode
    print(str(output), flush=True)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
