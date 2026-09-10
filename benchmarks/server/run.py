#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Externally paced real-node indexing/search benchmark and bounded adversarial stress."""
import argparse, concurrent.futures, datetime, hashlib, http.client, json, math, sys, threading, time, uuid
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "qa"))
from node import Node

DOCUMENTS = [line.split("\t", 1)[1] for line in (ROOT / "benchmarks/corpus/documents.tsv").read_text().splitlines()]

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def setup(node, profile):
    config = {"type": "homoglyph"}
    config["profile"] = profile
    index = {"settings": {"number_of_shards": 1, "number_of_replicas": 0,
             "analysis": {"filter": {"h": config}, "analyzer": {"h": {"tokenizer": "whitespace", "filter": ["h"]}}}},
             "mappings": {"properties": {"text": {"type": "text", "analyzer": "h"}}}}
    code, result = node.request("/homoglyph-benchmark", index, "PUT")
    require(code == 200, str(result))
    # A separate keyword field exercises rejection before a tokenizer splits long values.
    index["settings"]["analysis"]["analyzer"]["h"]["tokenizer"] = "keyword"
    code, result = node.request("/homoglyph-rejections", index, "PUT")
    require(code == 200, str(result))

def workload(node, seconds, rate, adversarial=False, profile="unicode_search_v1"):
    capacity = threading.BoundedSemaphore(32)
    start = time.monotonic() + 0.1
    count = int(seconds * rate)
    rows, dropped = [], 0
    samples = []
    local = threading.local()
    connections, connections_lock = [], threading.Lock()
    def request(*arguments):
        if not hasattr(local, "connection"):
            local.connection = http.client.HTTPConnection("127.0.0.1", node.port, timeout=30)
            with connections_lock:
                connections.append(local.connection)
        try:
            return node.request(*arguments, connection=local.connection)
        except (OSError, http.client.HTTPException):
            local.connection.close()
            raise
    def operation(number, intended):
        kind, accepted_docs, expected_rejection = "search", 0, False
        try:
            if adversarial and number % 8 == 0:
                kind, expected_rejection = "rejection", True
                rejected = "o😀" * 30 if profile == "unicode_expand_v1" and (number // 16) % 2 else "x" * 4097
                if (number // 8) % 2:
                    code, result = request("/homoglyph-rejections/_doc/bad", {"text": rejected}, "PUT")
                else:
                    code, result = request("/homoglyph-rejections/_search?allow_partial_search_results=false", {"query": {"match": {"text": rejected}}}, "POST")
                good = code >= 400
            elif adversarial and number % 8 == 1:
                kind = "adversarial-analysis"
                value = ("é" * 12, "rnrn", "m" * 4, "漢é😀")[(number // 8) % 4]
                code, result = request("/homoglyph-rejections/_analyze", {"analyzer": "h", "text": value}, "POST")
                good = code == 200
            elif number % 4 != 0:
                kind = "index"
                data = "".join(json.dumps(x, ensure_ascii=False) + "\n" for i in range(20) for x in (
                    {"index": {"_index": "homoglyph-benchmark", "_id": str((number * 20 + i) % 20000)}},
                    {"text": DOCUMENTS[(number + i) % len(DOCUMENTS)]}))
                code, result = request("/_bulk", data, "POST", "application/x-ndjson")
                good = code == 200 and not result.get("errors", True)
                accepted_docs = sum(item["index"]["status"] < 300 for item in result.get("items", []))
            else:
                query = DOCUMENTS[(number // 4) % len(DOCUMENTS)].split()[0]
                code, result = request("/homoglyph-benchmark/_search?allow_partial_search_results=false", {"query": {"match": {"text": query}}, "size": 0}, "POST")
                good = code == 200 and result.get("_shards", {}).get("failed", 1) == 0
            return (kind, (time.monotonic() - intended) * 1000, good, code, accepted_docs, expected_rejection,
                    None if good else str(result.get("error", result))[:1500])
        except Exception as error:
            return (kind, (time.monotonic() - intended) * 1000, False, type(error).__name__, 0, expected_rejection)
        finally:
            capacity.release()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            pending = set()
            for number in range(count):
                intended = start + number / rate
                time.sleep(max(0, intended - time.monotonic()))
                done = {future for future in pending if future.done()}
                rows.extend(future.result() for future in done)
                pending.difference_update(done)
                if capacity.acquire(blocking=False):
                    pending.add(executor.submit(operation, number, intended))
                else:
                    dropped += 1
                if number and number % max(1, int(rate * 60)) == 0:
                    code, stats = node.request("/_nodes/_local/stats/jvm,process,indices")
                    if code == 200:
                        samples.append(stats)
                    print(f"Completed scheduling {number}/{count} operations", flush=True)
            rows.extend(future.result() for future in pending)
    finally:
        for connection in connections:
            connection.close()
    elapsed = time.monotonic() - start
    summary = {}
    for kind in sorted({row[0] for row in rows}):
        selected = [row for row in rows if row[0] == kind]
        values = sorted(row[1] for row in selected if row[2])
        def percentile(q):
            return values[min(len(values) - 1, math.ceil(q * len(values)) - 1)] if values else None
        summary[kind] = {"operations": len(selected), "successes": sum(row[2] for row in selected),
                         "failed": sum(not row[2] for row in selected), "p50_ms": percentile(.5),
                         "p95_ms": percentile(.95), "p99_ms": percentile(.99),
                         "successful_documents": sum(row[4] for row in selected)}
    return {"duration_seconds": seconds, "elapsed_seconds": elapsed, "offered_requests_per_second": rate,
            "client_queue_capacity": 32, "workers": 8, "persistent_http_connections": True, "client_overload_drops": dropped,
            "latency_includes_scheduler_and_client_queue_delay": True, "operations": summary,
            "successful_documents_per_second": sum(row[4] for row in rows) / elapsed,
            "successful_queries_per_second": sum(row[0] == "search" and row[2] for row in rows) / elapsed,
            "failures": [row for row in rows if not row[2]][:20], "node_samples": samples}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--warmup", type=int, default=15)
    parser.add_argument("--rate", type=float, default=30)
    parser.add_argument("--stress", action="store_true")
    parser.add_argument("--profile", choices=["unicode_search_v1", "unicode_expand_v1"], default="unicode_search_v1")
    parser.add_argument("--output", type=Path, default=ROOT / "benchmarks/results/server")
    args = parser.parse_args()
    if args.seconds < 1 or not 1 <= args.warmup <= 1800 or not math.isfinite(args.rate) or args.seconds * args.rate < 1 or max(args.seconds, args.warmup) * args.rate > 200000:
        parser.error("Require a finite positive rate, at least one measured operation and at most 200000 scheduled operations per phase")
    args.output.mkdir(parents=True, exist_ok=True)
    artifact = ROOT / "adapters/elasticsearch-7/build/7.17.3/distributions/sharddeck-homoglyph-elasticsearch-7-0.1.0-SNAPSHOT-7.17.3.zip"
    reports = []
    for run_number in range(1):
        implementation = "homoglyph"
        node = Node("sharddeck-homoglyph-benchmark-" + ("stress" if args.stress else implementation) + "-" + uuid.uuid4().hex[:8],
                    "docker.elastic.co/elasticsearch/elasticsearch:7.17.3", artifact)
        report = {"implementation": implementation, "profile": args.profile,
                  "run_number": run_number, 
                  "warmup_seconds": min(args.warmup, args.seconds),
                  "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                  "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "corpus": json.loads((ROOT / "benchmarks/corpus/manifest.json").read_text()),
                  "purpose": "adversarial recovery" if args.stress else "paced development benchmark"}
        print("Starting " + implementation, flush=True)
        with node:
            setup(node, args.profile)
            workload(node, min(args.warmup, args.seconds), args.rate, profile=args.profile)
            _, report["before"] = node.request("/_nodes/_local/stats/jvm,process,indices")
            report["load"] = workload(node, args.seconds, args.rate, args.stress, args.profile)
            _, report["after"] = node.request("/_nodes/_local/stats/jvm,process,indices")
            code, health = node.request("/_cluster/health")
            require(code == 200 and health["status"] in ("green", "yellow"), "Node unhealthy after load")
            report["recovery"] = workload(node, min(15, args.seconds), args.rate, profile=args.profile)
        report["image_id"] = node.state["Image"]
        report["oom_killed"] = node.state["State"]["OOMKilled"]
        report["exit_code"] = node.state["State"]["ExitCode"]
        report["status"] = "passed" if not report["oom_killed"] and report["exit_code"] in (0, 143) and all(
            not report[phase]["failures"] and not report[phase]["client_overload_drops"] for phase in ("load", "recovery")) else "failed"
        (args.output / (str(run_number) + "-" + implementation + ".log")).write_text(node.logs)
        reports.append(report)
        (args.output / "verification.json").write_text(json.dumps(reports, indent=2) + "\n")
    return int(any(report["status"] != "passed" for report in reports))

if __name__ == "__main__":
    raise SystemExit(main())
