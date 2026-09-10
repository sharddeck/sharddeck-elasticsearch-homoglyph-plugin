# Real-engine integration

```sh
./gradlew build
./gradlew :adapters:elasticsearch-7:build -Pelasticsearch7.version=7.17.29
python3 qa/integration/run.py
# Selected targets: es7173 es7 es80 es87 es8 es90 es9 os2 os3
# Latest Elasticsearch patch per major, preserving the full matrix report:
python3 qa/integration/run.py es7 es8 es9 --output qa/integration/latest-elasticsearch.json
```

Each run builds a disposable image containing the matching plugin ZIP, starts an isolated node with a 512 MiB heap and 2 GiB container ceiling, checks analysis, profile/settings failures, indexing/search, bulk item isolation, query failure visibility and recovery, then stops/removes only that test container. Ports bind to loopback. The output report (default `verification.json`) is replaced with results for the requested targets and records artifact SHA-256, Docker image ID, engine/Lucene versions, heap and OOM status. The running engine version must match the requested image tag.

The stable API ignores unknown settings; the report records that limitation. Use `tools/validate_config.py` in deployment checks. Exact known settings and hard limits are validated in all adapters.

The current matrix results and exact artifact hashes are in [verification.json](verification.json). Integration checks behavior and rejection recovery; sustained memory-stress evidence is recorded separately under `qa/memory`. Engine tags are pinned in the harness and Docker matrix.
