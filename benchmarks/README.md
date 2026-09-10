# Benchmarks

JMH 1.37 measures the plugin's Unicode search and expansion pipelines with all safeguards enabled. The suite includes core processing, reused Lucene analyzers, a fixed multilingual corpus and cold initialization.

```sh
./gradlew build
python3 benchmarks/run_jmh.py --output benchmarks/results/my-run --note 'CPU, JDK and isolation details'
python3 benchmarks/summarize.py benchmarks/results/my-run
# Short execution check:
python3 benchmarks/run_jmh.py --quick --output benchmarks/results/smoke
```

The runner freezes the executable JAR and records its checksum, corpus, JDK, platform and exact commands. Steady-state runs use three forks, five 2-second warmups and ten 2-second measurements. GC profiling records allocation, and JSON retains samples/confidence intervals. Cold initialization uses one sample per fresh fork with no warmup. Quick mode only verifies harness execution.

`HomoglyphBenchmark` consumes every character from core processing and reused keyword analyzers. Inputs cover unchanged ASCII, supplementary characters, repeated confusables, ASCII skeleton changes and combining marks, each with original preservation off/on. One operation processes one input token.

`MultilingualBenchmark.documents` consumes a fixed batch of 20 synthetic documents through reused whitespace analyzers in both preservation modes. The [manifest](corpus/manifest.json) pins input checksum and language weights. One operation is a batch; divide allocation/time by 20 for per-document values. The corpus is general multilingual test data, not a measured production distribution.

`ExpansionBenchmark` measures single-span and multi-span variant generation, multilingual inputs, dense matched inputs and the unmatched fast path. It consumes every planned output and runs with `max_changed_positions` set to 1 and 4. Expansion output counts and allocations are reported separately from search-key normalization because the profiles have different semantics.

```sh
# Repeat separately at 1, 8, 32 and 128 threads on controlled hardware.
java -Xms512m -Xmx512m -jar benchmarks/jmh/build/libs/benchmarks.jar HomoglyphBenchmark -t 8 -prof gc
java -Xms512m -Xmx512m -jar benchmarks/jmh/build/libs/benchmarks.jar InitializationBenchmark -prof gc
```

## Real-node load and memory

```sh
python3 benchmarks/server/run.py --seconds 120 --rate 30 --output benchmarks/results/server
python3 benchmarks/server/run.py --stress --seconds 1800 --rate 20 --output benchmarks/results/server-stress
python3 qa/memory/run.py --seconds 1800
```

The server driver installs the ES7.17.3 package on an isolated node with bounded heap/container/CPU. Index requests contain 20 documents and normal traffic mixes indexing/search requests 3:1. Scheduling is externally paced; latency starts at the intended send time. Eight workers, persistent connections and a 32-operation queue bound client backlog. Queue drops and every bulk/query failure are counted. Index IDs wrap at 20,000 to bound stored data. Adversarial analysis and expected input-limit rejections accompany ordinary traffic, followed by recovery checks.

Reports include p50/p95/p99, successful document/query throughput, errors, JVM/CPU/index statistics, artifact/image hashes and OOM/exit status. Increase offered load in separate controlled runs to find sustainable throughput; a low-rate successful run does not establish saturation. One engine's load result does not qualify the entire matrix.

The core memory harness uses a 256 MiB heap, 1 GiB container ceiling and 1/8/32/128-worker phases, exercising preservation, abandonment, Unicode expansion, combining marks, malformed/oversized input and admission rejection. Preserve raw evidence and repeat full-duration stress on release artifacts.

[Rally](rally/README.md) and [OpenSearch Benchmark](opensearch/README.md) workloads use the same corpus. Production qualification requires controlled hardware, actual analyzer chains and representative document/value distributions. [Current verification](../docs/verification.md) distinguishes smoke checks from full performance evidence.
