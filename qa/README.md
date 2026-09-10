# Verification

`./gradlew build` runs deterministic Unicode/reference cases, bounds/allocation checks, concurrency/admission tests, and shared Lucene lifecycle/attribute contracts across six adapters.

- `python3 qa/integration/run.py`: nine actual engine installation/behavior targets; build the ES7.17.29 override first.
- `python3 qa/integration/coexistence.py`: host ICU plugin coexistence.
- `python3 qa/integration/java11.py`: ES7.17.3 on Java 11.
- `python3 qa/memory/run.py --seconds 1800`: four concurrency phases in an isolated 256 MiB JVM.
- `python3 benchmarks/server/run.py --stress --seconds 1800`: multilingual traffic, rejection and recovery.
- `python3 tools/package_manifest.py`: descriptors, contents, fingerprints, hashes and runtime SBOM audit.

Reports identify exact artifacts. See [verification evidence](../docs/verification.md) for completed checks and their limits.
