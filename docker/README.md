# Local engine matrix

Pinned to the newest stable release in each selected major, checked on **2026-09-07**:

- `es7`: Elasticsearch **7.17.29**, localhost port **9207**.
- `es8`: Elasticsearch **8.19.21**, localhost port **9208**.
- `es9`: Elasticsearch **9.5.3**, localhost port **9209**.
- `os1`: OpenSearch **1.3.20**, localhost port **9211**.
- `os2`: OpenSearch **2.19.6**, localhost port **9212**.
- `os3`: OpenSearch **3.8.0**, localhost port **9213**.
- `es7173`: Elasticsearch **7.17.3**, localhost port **9173**, retained as the minimum supported Elasticsearch version.

The versioned images are stock engines: **they do not have the ShardDeck plugin installed**. OpenSearch 1 is included as an additional historical test target; having its container does not establish plugin support.

Sources: [Elasticsearch 7 tags](https://api.github.com/repos/elastic/elasticsearch/git/matching-refs/tags/v7.), [Elasticsearch 8 tags](https://api.github.com/repos/elastic/elasticsearch/git/matching-refs/tags/v8.), [Elastic's image registry](https://www.docker.elastic.co/r/elasticsearch), and [OpenSearch's release catalog](https://opensearch.org/artifacts/by-version/). Tags are explicitly pinned so benchmark environments do not change underneath a run; update them deliberately when a newer patch is released.

## Use

Run commands from the repository root. Each service is an independent single-node cluster with its own data volume. Profiles prevent a bare `docker compose up` from launching the entire matrix.

```sh
# Add all latest-major containers to Docker without starting them.
docker compose --profile latest create

# Add the minimum supported version too.
docker compose --profile baseline create

# Run one target and wait for health.
docker compose up -d --wait es9
curl http://127.0.0.1:9209/

# Stop this target while preserving its data.
docker compose stop es9

# Verify all targets sequentially; nodes started by this script are stopped afterward.
python3 docker/smoke.py

# Or verify selected targets.
python3 docker/smoke.py es7 es9 os3

# Inspect this project's containers, including stopped ones.
docker compose --profile '*' ps -a
```

The smoke runner records engine/Lucene versions, architecture, image digests, and actual memory limits in `verification.json`. It checks stock analysis, cluster health, and graceful shutdown for nodes it starts, including Docker's OOM flag and exit status. It does not measure plugin correctness, OOM resilience, or performance. Existing running matrix services remain running; the script's node-by-node resource discipline assumes the other targets are initially stopped. Each invocation replaces the report with results for the requested services.

## Resource and isolation settings

Each container has a **512 MiB JVM heap**, **2 GiB container memory ceiling**, **2 CPU quota**, bounded logs, and no extra swap allowance. Services do not restart automatically. Published ports bind to **127.0.0.1**; authentication and TLS are disabled for this isolated development matrix. Do not expose these ports publicly.

Elasticsearch 9 receives a 90-second graceful shutdown allowance; the other targets use 30 seconds. Its first smoke run exceeded the shorter allowance and Docker force-stopped it without an OOM event.

Start one target at a time on the current Docker VM, which had roughly 8 GB RAM at setup. A `latest` profile start would launch six nodes with up to 12 GiB combined container limits, plus any other running applications. `create` allocates the containers and volumes without starting their engines.

The images select the host's native architecture. No forced amd64 emulation is used. Memory mapping is disabled for portable functional checks without changing the Docker VM's global `vm.max_map_count`. These shared-host settings are **not a production performance benchmark configuration**; throughput comparisons need a dedicated run with explicitly recorded CPU, heap, filesystem/mmap, concurrency, and engine settings.

Stop/remove only this project using `docker compose --profile '*' down`. Data volumes survive that command. Adding `--volumes` would delete this matrix's stored index data and should be intentional.

Plugin-installed images and correctness tests are created separately by `python3 qa/integration/run.py`; they do not modify these stock services.
