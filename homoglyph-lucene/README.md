# Shared Lucene source

Each adapter compiles this final token filter against its own engine's Lucene API. This source-only module publishes no cross-major Lucene binary; Gradle includes its sources in each adapter.

The filter preserves standard attributes, deep-copies bounded payloads only when multiple outputs require it, rejects unknown attribute graphs before snapshotting, carries positions across deleted terms, and releases borrowed core state and snapshots on completion, reset, close and failures. Shared contracts run independently in all six adapter modules.
