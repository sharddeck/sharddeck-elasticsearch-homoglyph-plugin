# Lucene contracts

All six adapters compile these tests against their actual Lucene dependencies. Tests cover positions/offsets and scalar attributes, downstream payload/type/offset/boost mutation, preservation, abandonment, keyword bypass, reset/reuse, deleted-token position carry, unsupported attributes, payload/type ceilings, snapshot admission and injected source read/end/reset/close failures.

Run through `./gradlew build`. The additional exact ES7 package runs the same contracts against its own dependencies.
