#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Check one Compose node at a time; preserve nodes that were already running."""

import argparse
import datetime
import json
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "--project-name", "sharddeck-homoglyph",
           "--file", str(ROOT / "compose.yaml"), "--profile", "*"]


def command(*args, capture=False):
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def api(port, path, body=None):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check(service, config):
    ids = command(*COMPOSE, "ps", "--status", "running", "-q", service, capture=True).strip()
    was_running = bool(ids)
    result = None
    try:
        command(*COMPOSE, "up", "--detach", "--no-recreate", "--wait",
                "--wait-timeout", "240", service)
        port = next(p["published"] for p in config["ports"] if p["target"] == 9200)
        expected = config["image"].rsplit(":", 1)[1]
        info = api(port, "/")
        require(info["version"]["number"] == expected, f"Unexpected version: {info}")
        require(info["cluster_name"] == f"homoglyph-{service}", "Unexpected cluster")
        if service.startswith("os"):
            require(info["version"].get("distribution") == "opensearch", "Unexpected distribution")
        health = api(port, "/_cluster/health")
        require(health["status"] in ("green", "yellow"), f"Unhealthy cluster: {health}")
        require(health["number_of_nodes"] == 1, "Expected an isolated single-node cluster")
        result = api(port, "/_analyze", {"analyzer": "standard", "text": "HELLO world"})
        require([t["token"] for t in result["tokens"]] == ["hello", "world"], "Analysis failed")
        stats = api(port, "/_nodes/_local/stats/jvm")
        heap = next(iter(stats["nodes"].values()))["jvm"]["mem"]["heap_max_in_bytes"]
        require(heap == 512 * 1024 * 1024, f"Unexpected JVM heap: {heap}")
        container = command(*COMPOSE, "ps", "-q", service, capture=True).strip()
        inspect = json.loads(command("docker", "inspect", container, capture=True))[0]
        require(not inspect["State"]["OOMKilled"], "Container was OOM-killed")
        require(inspect["HostConfig"]["Memory"] == 2 * 1024**3, "Missing container memory cap")
        require(inspect["HostConfig"]["MemorySwap"] == 2 * 1024**3, "Unexpected swap allowance")
        require(inspect["HostConfig"]["NanoCpus"] == 2_000_000_000, "Missing CPU cap")
        image = json.loads(command("docker", "image", "inspect", config["image"], capture=True))[0]
        result = {
            "service": service, "image": config["image"], "status": "passed",
            "version": info["version"]["number"], "lucene": info["version"]["lucene_version"],
            "architecture": image["Architecture"], "image_id": image["Id"],
            "repository_digests": image.get("RepoDigests", []), "port": int(port),
            "heap_bytes": heap, "container_memory_bytes": inspect["HostConfig"]["Memory"],
            "already_running": was_running,
        }
    finally:
        if not was_running:
            command(*COMPOSE, "stop", service)
            container = command(*COMPOSE, "ps", "--all", "-q", service, capture=True).strip()
            if container:
                state = json.loads(command("docker", "inspect", container, capture=True))[0]["State"]
                require(not state["Running"], "Container did not stop")
                require(not state["OOMKilled"], "Container was OOM-killed during shutdown")
                require(state["ExitCode"] in (0, 143), f"Unclean shutdown: exit {state['ExitCode']}")
                if result is not None:
                    result["shutdown_exit_code"] = state["ExitCode"]
                    result["oom_killed"] = state["OOMKilled"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("services", nargs="*", help="Defaults to every configured service, including 7.17.3")
    args = parser.parse_args()
    config = json.loads(command(*COMPOSE, "config", "--format", "json", capture=True))
    services = args.services or list(config["services"])
    if any(name not in config["services"] for name in services):
        parser.error("Unknown service; choose from " + ", ".join(config["services"]))
    report = {
        "checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "scope": "Stock engine startup, health, standard analysis, version, resource limits, and shutdown when started by this script; no homoglyph plugin installed",
        "results": [],
    }
    for service in services:
        print(f"Checking {service}...", flush=True)
        try:
            result = check(service, config["services"][service])
        except Exception as error:
            result = {"service": service, "status": "failed", "error": str(error)}
        report["results"].append(result)
        (ROOT / "docker" / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"{service}: {result['status']}", flush=True)
    return int(any(r["status"] != "passed" for r in report["results"]))


if __name__ == "__main__":
    sys.exit(main())
