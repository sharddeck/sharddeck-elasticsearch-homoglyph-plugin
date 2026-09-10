# Implementation and verification

The standalone Unicode plugin is a **0.1.0-SNAPSHOT development build**. It implements `unicode_search_v1` search normalization and `unicode_expand_v1` bounded Unicode confusable expansion, with semantics and data pinned to ICU4J 78.1 / Unicode 17. See [design](design.md).

## Functional checks — 2026-09-09

The Gradle build and additional exact ES7.17.29 package passed **94 tests across eleven suites**, with no failures, errors or skips. Coverage includes 5,000 seeded Unicode/reference cases, expansion cardinality and ordering, multilingual fixtures, input/output/stream boundaries, malformed input, original preservation, concurrent admission, abandonment, attribute mutation and source lifecycle failures. ICU allocation checks cover adversarial inputs against conservative reservations. [Contract results](../qa/contracts/verification.json).

Actual plugin installation, default and explicit Unicode analysis, original preservation, indexing/search, invalid settings, bulk item isolation, query rejection and recovery passed on Elasticsearch **7.17.3, 7.17.29, 8.0.0, 8.7.0, 8.19.21, 9.0.0 and 9.5.3**, plus OpenSearch **2.19.6 and 3.8.0**. Each node used a 512 MiB heap and 2 GiB container limit; none was OOM-killed. [Engine results and exact package hashes](../qa/integration/verification.json).

ES7.17.3 also passed with **Java 11.0.32**. [Java 11 result](../qa/integration/java11.json). The default build uses Java 17 with newer adapter toolchains; [bytecode audit](../qa/bytecode.json) verifies the declared Java release ceilings for all seven packages.

The Unicode bounds generator reproduced all 20,937 entries byte-for-byte from pinned ICU. [Generation evidence](../qa/data-generation.json). The package audit verifies four runtime JARs, allowed class namespaces, Unicode-only resource/profile inventory, descriptor registration, ICU/data checksums, profile fingerprints, source notices and SBOM metadata. The package manifest associates engine results only with matching ZIP hashes.

## Fresh smoke evidence

The final core/data/ICU runtime passed **120 seconds** of isolated memory stress at **1, 8, 32 and 128 workers**, using a **256 MiB JVM heap** and **1 GiB container ceiling**. Every phase returned borrowed workspace to zero; peak shared reservations were **2,096,640 bytes**, below the 2 MiB budget. There was no OOM and the process exited normally. [Memory result](../qa/memory/verification.json).

The Java 17 JMH smoke suite completed **44 cases** covering search-key processing, expansion at one and four changed spans, reused analyzers, multilingual batches, both preservation modes and cold initialization. GC profiling, raw samples, commands, environment and artifact hashes are under `benchmarks/results/standalone-expansion-smoke/`; its summary explicitly marks these as harness smoke measurements.

A **30-second** paced ES7.17.3 server smoke run passed multilingual indexing/search, adversarial analysis, expected limit rejection and ordinary-traffic recovery with no unexpected failures or client queue drops. Results and package hash are under `benchmarks/results/standalone-server-smoke/`.

These runs verify functionality and harness execution. Full-duration JMH, 30-minute stress, ICU coexistence, saturation and production tail-latency qualification have not been repeated for this standalone build. Use the [benchmark protocol](../benchmarks/README.md) on controlled, representative hardware before setting release performance claims. Plugin admission does not bound all memory used elsewhere in a node.
