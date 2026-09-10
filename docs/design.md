# Normalization and usage

The installed plugin ID is `analysis-homoglyph`; its token filter is `homoglyph`. Bare `"homoglyph"` syntax defaults to `unicode_search_v1`. Use `unicode_expand_v1` explicitly when variant generation is desired.

```json
{"type":"homoglyph","profile":"unicode_search_v1","preserve_original":false}
```

## Profiles

A profile is an immutable, named normalization policy. The `unicode_search_v1` search-key profile uses ICU4J 78.1 / Unicode 17.0 in this order:

1. NFKC_Casefold.
2. NFD decomposition.
3. Remove nonspacing marks whose most recent non-Mark base has `Script=Latin`. Spacing/enclosing marks do not reset that base; a non-Mark character does.
4. `SpoofChecker.getSkeleton()`.

Examples with equal keys include `paypal`/`pаypаl`, `microsoft`/`rnicrosoft`, composed/decomposed Latin accents, and `ss`/`ß`. ASCII can change: `microsoft` becomes `rnicrosoft`. Keys may contain non-ASCII characters. This lossy search policy does not implement complete Unicode 17 bidi skeleton conformance or assign a spoofing verdict.

Malformed UTF-16 rejects. Empty keys are dropped and their position increments carried forward. `preserve_original: true` emits the nonempty original first, followed by a distinct nonempty normalized key at the same position. Unchanged terms appear once. The output count is therefore at most two per input. Every successful analysis uses the same normalization policy regardless of memory pressure; insufficient admission raises an operation error.

The [profile manifest](../homoglyph-data/src/main/resources/io/sharddeck/homoglyph/data/profiles.json) records algorithm, ICU and resource checksums. An update that changes keys or variants requires a new profile ID and a reindex decision. Unicode decisions use pinned ICU data rather than host JDK character tables.

`unicode_expand_v1` is an independent, deterministic variant profile. Its data was generated offline by enumerating Unicode scalars with ICU 78.1, applying `SpoofChecker.getSkeleton()` to a fixed point, and grouping each fixed-point spelling with its scalar spellings. Runtime uses a compact longest-match trie over the pinned, prefix-free spellings. It does not case-fold or strip accents.

Expansion emits the original token first, followed by substitutions at one or more matched spans. The default `max_changed_positions=1` emits each single-span variant; values through 4 permit bounded combinations. `max_output_tokens_per_input` defaults to 1,024 and the processor counts the complete plan before borrowing a workspace or emitting a token. `preserve_original` is accepted for shared configuration compatibility but is intrinsic to this profile and does not add a duplicate.

```json
{"type":"homoglyph","profile":"unicode_expand_v1"}
```

`é` emits `é` and `é`; `éñ` emits the original plus each one-span substitution, and with `max_changed_positions=2` also emits the combined substitution. `m` emits its bounded Unicode confusable spellings, including `rn` and mathematical letter forms. Variant ordering is stable and the rightmost alternative changes fastest.

## Settings

Supported settings are `profile`, `preserve_original` and the downward-only [resource limits](performance-and-memory.md). Defaults require no settings beyond `type: homoglyph`. There are no mutable dictionaries or unlimited mode.

Known settings are validated when the filter factory is created. Classic adapters reject unknown plugin settings, excluding exact host-injected index metadata. Elasticsearch's stable typed settings API ignores undeclared keys; run `tools/validate_config.py` before deployment to catch spelling errors. The example's mapping `_meta` fingerprints are an offline validation convention, not automatic cross-node enforcement.

## Using the filter

For multilingual documents, retain original text and add a normalized text subfield. Use the same analyzer at indexing and query time through `match`. A raw `term` query bypasses analysis. Wildcard, prefix, regexp and fuzzy query behavior requires separate validation.

A `standard` tokenizer is suitable for prose but may split or discard characters before the filter sees them. A `keyword` tokenizer treats an entire identifier as one input. The [example index](examples/index.json) demonstrates both. Search its `name.visual` and `identifier.visual` subfields; display original values from `_source`.

The filter is intended for text analyzers. Search normalization can emit zero or two terms; expansion can emit up to 1,024 terms under its default per-input ceiling. It is not advertised for keyword-field normalizers. Validate synonym/graph-sensitive and custom attribute chains with the actual host version. Changed analysis semantics require a new index and reindexing; changing a plugin binary does not update already stored terms.

## Architecture and lifecycle

The core has no Lucene or server API dependency. Each adapter compiles the shared Lucene source against its host's Lucene version. Immutable Unicode data and scratch admission are shared across factories, fields and indexes within the installed plugin classloader. There are no token caches, ThreadLocal pools or plugin executors.

Only bounded standard attributes are saved for two-output tokens: position, position length, offsets, type, flags, payload, keyword, term frequency and query boost. Separate payload arrays protect saved state from consumer mutation. Unknown attributes reject only when a snapshot is required. Reset, end, close and failure paths release borrowed state; an analysis failure requires reset before reuse.

Keyword-marked tokens bypass transformation after malformed-input and resource checks. Offsets refer to original input even when key lengths differ. Removed terms retain Lucene position semantics. The verified identity-ASCII fast path avoids ICU work and plugin allocation for unchanged ASCII.

Each package bundles ICU; no host `analysis-icu` plugin is required. Optional coexistence tests exercise both plugins separately and in a chain. Install matching artifact checksums on every node and validate actual indexing/search before directing traffic to a new index.

## Independent expansion

The streaming engine boundary supports independently defined expansion profiles. Such a profile may emit more than the default normalized key and preserved original. Its rules and data must have their own versioned definition, deterministic ordering, and pinned provenance. Count the complete output plan before allocating, enforce input/output/stream and shared-memory ceilings, emit lazily, and reject an over-budget plan before producing partial results.

The current build implements both the Unicode search-key and bounded Unicode expansion profiles. The expansion profile has dedicated correctness, allocation, concurrency and real-engine checks; its pinned data and limits are part of the profile contract.
