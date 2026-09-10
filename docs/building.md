# Build and install

Use JDK 17 and `./gradlew` (Gradle 9.7.1). The Gradle daemon, core, tools, benchmarks and ES7/8 compiler/test toolchains default to Java 17. ES9 and OpenSearch 2 use Java 21; OpenSearch 3 uses Java 25. Core/data/tools and ES7 retain Java 11 bytecode compatibility. Production uses the engine's bundled JDK.

The plugin uses independent SemVer releases; the current line is `0.1.0-SNAPSHOT`. Host compatibility and release rules are defined in [versions and compatibility](releases.md).

The checked-in daemon JVM criteria select Java 17 even when another JDK is on `PATH`. Gradle detects installed toolchains and downloads missing JDKs through the pinned Foojay resolver into its user-home cache. The first full build therefore needs network access for missing Java 21/25 toolchains; an ES7/8-only build needs only Java 17. CI starts on Java 17 and uses the same configuration. [Gradle toolchain provisioning](https://docs.gradle.org/current/userguide/toolchains.html#sec:provisioning).

The checked-in wrapper verifies the Gradle distribution SHA-256. This is a Gradle-only build; no Maven installation, wrapper, project files or local Maven repository are required. Dependencies come from Central.

```sh
./gradlew build
# Additional exact-version ES7 package:
./gradlew :adapters:elasticsearch-7:build -Pelasticsearch7.version=7.17.29
python3 tools/package_manifest.py
```

`build` runs core tests and the shared Lucene contracts against six independently compiled adapters. It builds the JMH and offline-tool executables and production plugin ZIPs. Docker integration is run separately. `package_manifest.py` checks ZIP contents, descriptors and absence of host/Lucene/JMH classes, then collects artifacts with checksums and per-package CycloneDX SBOMs in `dist/`.

Plugin ZIPs are written to `adapters/<adapter>/build/<engine-api-version>/distributions/`. Exact-version builds have independent output directories, so the ES7 baseline and latest patch packages coexist. `./gradlew clean` removes all project build outputs, including those extra versions. JARs are under each component's `build/libs/`; test XML and HTML reports are under its build directory's `test-results/test/` and `reports/tests/test/`. Archive ordering and timestamps are reproducible.

Useful component tasks:

```sh
./gradlew :homoglyph-core:test
./gradlew :adapters:elasticsearch-stable-9:build
./gradlew :benchmarks:jmh:executableJar :tools:executableJar
python3 qa/integration/run.py es7 es8 es9 --output qa/integration/latest-elasticsearch.json
```

## Engine targets

The compatibility policy and verified matrix are in [versions and compatibility](releases.md). Exact test results and artifact hashes are in [integration evidence](../qa/integration/verification.json). The package manifest only associates a test with a package when the SHA-256 matches.

- Elasticsearch classic 7: exact 7.17.3 and 7.17.29 packages; Java release 11.
- Elasticsearch classic early 8: exact 8.0.0 package; Java release 17. Build other 8.0–8.6 versions with `-Pelasticsearch8.version=VERSION` and test that exact version.
- Elasticsearch stable 8: compile API 8.7.0; tested on 8.7.0 and 8.19.21; Java release 17.
- Elasticsearch stable 9: compile API 9.0.0; tested on 9.0.0 and 9.5.3; Java release 21.
- OpenSearch 2: exact 2.19.6 package; Java release 21.
- OpenSearch 3: exact 3.8.0 package; Java release 25.

Classic version checks require exact descriptors and recompilation; overriding a version alone is not runtime verification. Stable plugins are portable within their API's major, subject to tested behavior. No claim is made that every intervening patch has been tested. OpenSearch 1.3.20 remains a stock Docker historical target, without a plugin adapter.

## Installation

Install the matching ShardDeck ZIP on a stopped node or while building its immutable image.

```sh
# Example: Elasticsearch 7.17.3; use absolute paths on the target machine.
bin/elasticsearch-plugin install --batch file:///absolute/path/sharddeck-homoglyph-elasticsearch-7-0.1.0-SNAPSHOT-7.17.3.zip
# Start/restart the node and verify _cat/plugins and _analyze.
```

OpenSearch uses `bin/opensearch-plugin`. Elasticsearch stable 8/9 uses the same Elasticsearch installer with the corresponding stable ZIP. `analysis-icu` is not required: each package includes the pinned ICU4J dependency.

Install the same artifact on all relevant nodes, validate configuration before rollout, then run smoke analysis and an index/query probe before admitting traffic. See [normalization and usage](design.md). This repository does not automatically alter a production cluster.

## Components

- `homoglyph-data`: pinned Unicode bounds resources and profile fingerprints.
- `homoglyph-core`: bounded processing and memory admission; no Lucene/server API.
- `homoglyph-lucene`: shared source compiled into each adapter against its own Lucene ABI.
- `adapters`: four classic and two stable adapters; host dependencies are compile-only.
- `qa`: core/Lucene contracts, actual installation checks, isolated low-heap stress and coexistence tests.
- `benchmarks`: JMH, a multilingual corpus, Rally/OpenSearch workloads and paced HTTP driver.
- `tools`: executable profile/validation/analysis CLI, offline generator and packaging audit.

Only the adapter, core, data and ICU JARs belong in a plugin ZIP. Project code is licensed under [MPL 2.0](../LICENSE); third-party components retain their own licenses. Plugin ZIPs include `LICENSE`, `NOTICE`, `SOURCE.md` and third-party notices under `licenses/`. Project JARs also carry the project notices in `META-INF/` and their corresponding source under `META-INF/homoglyph/sources/`; see [source availability](../SOURCE.md). The package audit verifies these notices and records the project license in the CycloneDX SBOMs.
