#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Generate deterministic Rally and OpenSearch Benchmark workloads from the fixed multilingual corpus."""
import argparse, hashlib, json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--documents", type=int, default=100000)
    parser.add_argument("--profile", choices=["unicode_search_v1", "unicode_expand_v1"], default="unicode_search_v1")
    args = parser.parse_args()
    if args.documents < 20 or args.documents > 1000000 or args.documents % 20:
        parser.error("--documents must be a multiple of 20 between 20 and 1000000")
    documents = [line.split("\t", 1)[1] for line in (ROOT / "benchmarks/corpus/documents.tsv").read_text().splitlines()]
    for directory, filename in [("rally", "track.json"), ("opensearch", "workload.json")]:
        path = ROOT / "benchmarks" / directory
        digest = hashlib.sha256()
        with (path / "documents.json").open("wb") as output:
            for i in range(args.documents):
                line = (json.dumps({"text": documents[i % len(documents)]}, ensure_ascii=False) + "\n").encode()
                digest.update(line)
                output.write(line)
        config = {"type": "homoglyph", "profile": args.profile}
        index = {"settings": {"number_of_shards": 1, "number_of_replicas": 0,
                 "analysis": {"filter": {"h": config}, "analyzer": {"h": {"tokenizer": "whitespace", "filter": ["h"]}}}},
                 "mappings": {"properties": {"text": {"type": "text", "analyzer": "h"}}}}
        (path / "index.json").write_text(json.dumps(index, indent=2) + "\n")
        queries = [{"operation": {"name": f"query-{i:02}", "operation-type": "search", "index": "homoglyph-benchmark",
                    "body": {"query": {"match": {"text": text.split()[0]}}, "size": 0},
                    "request-params": {"allow_partial_search_results": "false"}},
                    "clients": 1, "warmup-time-period": 30, "time-period": 120, "target-throughput": 1}
                   for i, text in enumerate(documents)]
        workload = {"version": 2, "description": "Fixed multilingual homoglyph indexing throughput and paced search",
                    "meta": {"profile": args.profile, "corpus_sha256": digest.hexdigest()},
                    "indices": [{"name": "homoglyph-benchmark", "body": "index.json"}],
                    "corpora": [{"name": "general-multilingual-v1", "documents": [{"source-file": "documents.json",
                        "document-count": args.documents, "uncompressed-bytes": (path / "documents.json").stat().st_size,
                        "target-index": "homoglyph-benchmark"}]}],
                    "schedule": [{"operation": {"operation-type": "create-index"}},
                                 {"operation": {"operation-type": "cluster-health", "request-params": {"wait_for_status": "green"}}},
                                 {"operation": {"operation-type": "bulk", "bulk-size": 1000}, "clients": 4},
                                 {"operation": {"operation-type": "refresh"}},
                                 {"parallel": {"tasks": queries}}]}
        (path / filename).write_text(json.dumps(workload, indent=2, ensure_ascii=False) + "\n")
        print(f"Generated {directory}: {args.documents} documents, SHA-256 {digest.hexdigest()}")

if __name__ == "__main__":
    main()
