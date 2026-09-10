#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Run memory stress against an immutable runtime snapshot in a resource-limited container."""
import argparse
import datetime
import hashlib
import json
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--output", type=Path, default=ROOT / "qa/memory")
    args = parser.parse_args()
    if not 4 <= args.seconds <= 86400:
        parser.error("--seconds must be between 4 and 86400")
    args.output.mkdir(parents=True, exist_ok=True)
    core = (ROOT / "homoglyph-core/build/libs/homoglyph-core-0.1.0-SNAPSHOT.jar").read_bytes()
    data = (ROOT / "homoglyph-data/build/libs/homoglyph-data-0.1.0-SNAPSHOT.jar").read_bytes()
    plugin = next((ROOT / "adapters/elasticsearch-7/build/7.17.3/distributions").glob("*-7.17.3.zip"))
    with zipfile.ZipFile(plugin) as archive:
        icu = archive.read("icu4j-78.1.jar")
    digest = hashlib.sha256(core + data + icu).hexdigest()
    source = (ROOT / "qa/memory/MemoryStress.java").read_bytes()
    harness_digest = hashlib.sha256(source).hexdigest()
    runtime = ROOT / "qa/memory/build/runtime" / (digest + "-" + harness_digest[:12])
    runtime.mkdir(parents=True, exist_ok=True)
    for name, blob in {"core.jar": core, "data.jar": data, "icu.jar": icu, "MemoryStress.java": source}.items():
        path = runtime / name
        if path.exists():
            if path.read_bytes() != blob:
                raise RuntimeError("Immutable runtime mismatch: " + str(path))
        else:
            path.write_bytes(blob)
    if not (runtime / "MemoryStress.class").exists():
        subprocess.run(["javac", "--release", "11", "-cp", str(runtime / "core.jar"), "-d", str(runtime), str(runtime / "MemoryStress.java")], check=True)
    name = "sharddeck-homoglyph-memory-" + digest[:12] + "-" + uuid.uuid4().hex[:8]
    image = "docker.elastic.co/elasticsearch/elasticsearch:9.5.3"
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with tempfile.TemporaryDirectory(prefix="homoglyph-memory-owner-") as temporary:
        cidfile = Path(temporary) / "container-id"
        command = ["docker", "run", "--cidfile", str(cidfile), "--name", name, "--label", "io.sharddeck.homoglyph.qa=true",
                   "--memory", "1g", "--memory-swap", "1g", "--cpus", "2", "--pids-limit", "512",
                   "--mount", f"type=bind,src={runtime},dst=/work,readonly", "--entrypoint", "/usr/share/elasticsearch/jdk/bin/java", image,
                   "-Xms256m", "-Xmx256m", "-Xss256k", "-cp", "/work:/work/core.jar:/work/data.jar:/work/icu.jar", "MemoryStress", str(args.seconds)]
        try:
            try:
                completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=args.seconds + 240)
                output, exit_code = completed.stdout, completed.returncode
            except subprocess.TimeoutExpired:
                exit_code = 124
                output = "Memory harness timed out\n"
            if not cidfile.exists():
                raise RuntimeError("Docker did not create a test container: " + output)
        finally:
            # A cidfile proves ownership; never remove a pre-existing container after a name collision.
            if cidfile.exists():
                container = cidfile.read_text().strip()
                state = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
                if state["State"]["Running"]:
                    subprocess.run(["docker", "stop", "--timeout", "10", container], check=True, stdout=subprocess.DEVNULL)
                    state = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
                output = subprocess.check_output(["docker", "logs", container], text=True, stderr=subprocess.STDOUT)
                subprocess.run(["docker", "rm", container], check=True, stdout=subprocess.DEVNULL)
    (args.output / "stress.log").write_text(output)
    rows = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
    passed = exit_code == 0 and not state["State"]["OOMKilled"] and len(rows) == 4 and all(
        row["borrowed"] == 0 and row["peak_reservation"] <= 2097152 for row in rows)
    report = {"started_at": started, "duration_seconds": args.seconds, "runtime_sha256": digest, "harness_sha256": harness_digest,
              "image_id": state["Image"], "heap_bytes": 268435456, "container_memory_bytes": state["HostConfig"]["Memory"],
              "oom_killed": state["State"]["OOMKilled"], "exit_code": exit_code, "phases": rows, "status": "passed" if passed else "failed"}
    (args.output / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
