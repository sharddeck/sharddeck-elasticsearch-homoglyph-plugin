# ShardDeck Homoglyph Analysis

Unicode search normalization and bounded confusable expansion for Elasticsearch and OpenSearch. The `homoglyph` token filter uses the versioned `unicode_search_v1` search-key profile by default; `unicode_expand_v1` is an explicit variant-generating profile.

Input `pаypаl` and `paypal` produces the same key. Latin accents, case and visually confusable characters normalize through a fixed pipeline. Expansion can additionally emit pinned Unicode confusable spellings, with strict preflight cardinality and memory limits.

```sh
# Build with JDK 17; Gradle selects newer toolchains for adapters that require them.
./gradlew build
python3 tools/package_manifest.py
python3 tools/validate_config.py docs/examples/index.json
```

This **0.1.0-SNAPSHOT development build** includes six adapters, installable ZIPs, bounded memory admission, strict resource ceilings, lifecycle cleanup, JMH and real-engine test harnesses. See [verification](docs/verification.md) for current evidence.

Packages are collected in `dist/` with checksums, descriptors, runtime components and artifact-matched engine verification. Classic plugins require the exact engine version; Elasticsearch stable adapters are separate for majors 8 and 9.

- [Build and install](docs/building.md): Gradle, toolchains, engine targets and packages.
- [Versions and compatibility](docs/releases.md): SemVer policy, release gates and the supported engine matrix.
- [Normalization and usage](docs/design.md): profile semantics, settings and analyzer behavior.
- [Performance and memory](docs/performance-and-memory.md): enforced limits and allocation model.
- [Benchmarks](benchmarks/README.md): JMH, multilingual corpus and paced server load.
- [Docker matrix](docker/README.md): isolated engine verification.
- [Offline tools](tools/README.md): validation, analysis and data generation.
- [Example index](docs/examples/index.json): original fields and normalized search subfields.

The filter uses a fixed-size workspace pool and bounded shared reservations, with no token cache, plugin worker threads or runtime downloads. Resource violations reject the operation. These safeguards bound plugin work; total node memory also includes tokenizers, indexing, queries and other components.

## License

ShardDeck Homoglyph Analysis is licensed under the [Mozilla Public License 2.0](LICENSE) (`MPL-2.0`), except for third-party material identified in [NOTICE](NOTICE). Commercial use and redistribution are permitted under its terms. When distributing modified MPL-covered files, make their source available to recipients under MPL 2.0 and preserve applicable notices.

Project JARs include their corresponding source; see [source availability](SOURCE.md). Third-party components retain their own licenses.
