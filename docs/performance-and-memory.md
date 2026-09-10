# Performance and memory contract

Search normalization produces at most one key and an optional original. Expansion produces a bounded original-plus-variants plan. Both modes compute admission bounds before work and check the complete output plan before emission. The identity-ASCII path uses the incoming buffer without a workspace or plugin-created per-token objects after initialization.

## Enforced ceilings

Analyzer settings may lower these positive ceilings, never raise or disable them:

- `max_input_utf16_units`: 4,096 per input, including keyword-marked tokens.
- `max_output_utf8_bytes`: 16,384 per emitted term.
- `max_output_bytes_per_input`: 65,536 aggregate emitted bytes per input.
- `max_output_tokens_per_input`: 1,024 expansion outputs per input.
- `max_changed_positions`: 1 by default, at most 4 expansion spans.
- `max_payload_bytes`: 4,096 and `max_type_utf16_units`: 256 for two-output snapshots.
- `max_input_tokens_per_stream` and `max_output_tokens_per_stream`: 65,536 each.
- `max_input_utf16_units_per_stream` and `max_output_utf8_bytes_per_stream`: 8,388,608 each.

Stream counters reset with the analyzer stream. Removed tokens still consume input budgets. Multiple values, fields or analyzers can start distinct streams, so these are not whole-document or request limits.

## Admission and retention

One installed plugin classloader uses a shared budget of `min(64 MiB, JVM maximum heap / 128)`. Workspaces, retained idle pool capacity, normalization temporaries and attribute snapshots share this budget. Admission uses overflow-safe atomic reservations. Idle workspaces can be evicted when a new allocation needs their capacity; active work is never evicted.

The pool contains at most 128 idle workspaces. Search workspaces hold two character arrays. Expansion workspaces additionally hold one input-offset and one variant-ID array, sized to the number of matched spans. Capacities round to powers of two, with a minimum of 16. The conservative reservation is `1024 + 2 × inputCapacity + 2 × outputCapacity + 8 × expansionSlotCapacity` bytes, including object/array overhead allowance. A smallest search workspace reserves 1,088 bytes; expansion slot capacity is always charged before allocation. Used characters and slot metadata are cleared on return. No output collections or per-token result cache are retained.

Before constructing ICU strings, the search processor sums pinned per-scalar decomposition/skeleton bounds and reserves conservative temporary capacity, with a 2 MiB per-token temporary ceiling. Expansion uses a checksum-verified compact trie and preflights its complete variant plan before borrowing arrays. ICU intermediates are short-lived; their reservation remains active until token completion. Shared immutable ICU/data tables and JVM overhead are outside scratch accounting.

Two-output snapshots validate attribute sizes before copying. At most 4,096 payload bytes and 256 type UTF-16 units are copied; saved and emitted payload buffers are distinct. Resource violations raise fixed-reason, text-free analysis errors. No successful key changes under memory pressure. End/reset/close/failure return borrowed state, and tests cover abandonment and consumer mutation.

The shared guard is independent of host circuit breakers. It does not account for all tokenizer, Lucene, request, native or unrelated node memory. Bound bulk sizes, concurrent requests and values per document in deployment as well.

## Measurement

[JMH](../benchmarks/README.md) measures core processing, reused analyzers, multilingual batches and cold initialization. Consume every output, include allocation/GC metrics, preserve raw samples and record JDK, hardware, corpus and artifact hashes. Test both original-preservation modes. Use separate 1/8/32/128-thread runs to expose pool contention and admission pressure; report rejection counts instead of treating fast failures as useful throughput.

The paced server driver reports accepted documents/queries, latency including queue delay, failures, node statistics and recovery. Low-heap stress checks retained heap, shared reservations and abandoned-token cleanup. Dedicated production-shaped hardware is required to set defensible throughput, saturation and tail-latency thresholds. See [verification](verification.md) for completed checks; harness smoke timings are not release certification.
