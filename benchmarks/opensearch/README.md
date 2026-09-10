# OpenSearch Benchmark workload

Generate the fixed corpus and index definition, then run against a disposable node with the selected plugin installed:

```sh
python3 benchmarks/generate_workloads.py --documents 100000 --profile unicode_search_v1
opensearch-benchmark run --pipeline=benchmark-only --workload-path=benchmarks/opensearch --target-hosts=127.0.0.1:9200 --on-error=abort
```

The workload creates `homoglyph-benchmark`, indexes at unrestricted throughput with four clients, refreshes, then runs 20 externally paced multilingual queries for 120 seconds after a 30-second warmup. Each query receives one request/second, preserving the 20-document language weights. Use a fresh empty test cluster per run. Existing index names are never deleted automatically by this workload.

Pin the plugin package, data, hardware, engine, heap, shard count and offered load for repeatable measurements. Record accepted throughput, latency (including scheduling delay), service time, errors, GC, CPU, heap and index size; do not turn a completed run into a release pass without evaluating the gates in [the benchmark protocol](../README.md).

The generated JSON and corpus digest are reproducible. `documents.json` is generated locally and excluded from version control. The standard harness run is optional tooling; this repository's dependency-free [server driver](../server/run.py) also measures real indexing/search and runs adversarial recovery tests.

References: [Rally track format](https://esrally.readthedocs.io/en/stable/track.html), [OpenSearch workload format](https://docs.opensearch.org/latest/benchmark/reference/workloads/index/).
