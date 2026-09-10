# Versions, releases and compatibility

## Plugin version

The distribution version is independent of the Elasticsearch or OpenSearch version and follows SemVer:

```text
MAJOR.MINOR.PATCH
```

The current development line is `0.1.0-SNAPSHOT`. The first stable release is planned as `1.0.0` after the release gates in this document pass. `gradle.properties` is the source of truth for the version; stable tags must be named `vX.Y.Z` and match it exactly.

- **MAJOR**: incompatible adapter/API changes, removal or rename of settings, or a change to the default profile.
- **MINOR**: new profiles, settings, adapters, or verified engine targets that preserve existing profile output and settings.
- **PATCH**: security, correctness, packaging, or performance fixes that preserve profile output and configuration behavior.

Profiles are separate immutable contracts. A change to emitted keys, expansion variants, ordering, pinned ICU data, or Unicode resources requires a new profile ID and reindexing. Existing profile IDs are never silently changed in a patch or minor release.

Pre-releases use SemVer identifiers such as `1.0.0-rc.1`. `*-SNAPSHOT` versions are development artifacts and are not supported for production rollout.

## Release artifacts

Each release produces one ZIP per adapter and exact host API version. ZIP names are:

```text
sharddeck-homoglyph-<adapter>-<plugin-version>-<engine-version>.zip
```

Stable releases must include:

1. Reproducible Gradle archives built with the checked-in wrapper.
2. `dist/SHA256SUMS` and a per-artifact CycloneDX SBOM.
3. Descriptor values matching the plugin, adapter, Java release, and host version.
4. Package audit and matching-engine integration evidence.
5. The pinned Unicode resource/profile fingerprints and source/license notices.

The six adapter families are built together. Elasticsearch 7 also has an additional exact-version package for the minimum and latest selected 7.17 patch, so the current matrix produces seven ZIPs.

Release sequence:

```sh
./gradlew clean build
python3 tools/validate_config.py docs/examples/index.json
python3 tools/package_manifest.py
python3 qa/integration/run.py
python3 qa/integration/java11.py
python3 benchmarks/run_jmh.py --quick --output benchmarks/results/release-smoke
```

Run full-duration memory and server qualification on controlled hardware before promoting a candidate to `1.0.0`; smoke measurements do not establish throughput or tail-latency guarantees.

## Compatibility matrix

“Verified” means the exact package was installed and tested against that engine version. Classic adapters are compiled and installed for an exact host version. Stable Elasticsearch adapters use the host stable API and are intended for compatible patch releases in that major, but only the listed versions are release-qualified.

| Engine line | Adapter | Package rule | Java release | Verified versions |
|---|---|---|---:|---|
| Elasticsearch 7.17.x | `elasticsearch-7` | Exact engine version | 11 | 7.17.3, 7.17.29 |
| Elasticsearch 8.0–8.6.x | `elasticsearch-8` | Exact engine version | 17 | 8.0.0 |
| Elasticsearch 8.7.x–8.x | `elasticsearch-stable-8` | Stable API family | 17 | 8.7.0, 8.19.21 |
| Elasticsearch 9.x | `elasticsearch-stable-9` | Stable API family | 21 | 9.0.0, 9.5.3 |
| OpenSearch 2.19.x | `opensearch-2` | Exact engine version | 21 | 2.19.6 |
| OpenSearch 3.8.x | `opensearch-3` | Exact engine version | 25 | 3.8.0 |
| OpenSearch 1.x | — | Unsupported; stock Docker target only | — | 1.3.20 container smoke |

Support boundaries:

- Elasticsearch `7.17.3` is the minimum supported engine version.
- For classic adapters, changing only the ZIP filename or descriptor is invalid; rebuild against the exact engine version.
- An engine patch not listed as verified is buildable only when its adapter accepts that exact API; it is not automatically release-certified.
- The engine’s bundled JDK is the production runtime. Java releases above describe the adapter bytecode/runtime requirement.
- Every node in a cluster must use the same plugin release and matching adapter ZIP.

## Support policy

The latest stable plugin minor receives normal fixes. The previous major receives critical and security fixes while its supported engine matrix remains available. New engine majors and new Unicode behavior are added in a minor release when they preserve existing profile contracts; incompatible changes require a major release and migration notes.

