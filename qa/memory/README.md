# Low-heap memory stress

```sh
./gradlew build
python3 qa/memory/run.py --seconds 1800
```

The runner freezes core/data/ICU JARs by combined SHA-256 before starting a resource-limited container. It runs four phases at 1, 8, 32 and 128 simultaneous workers using a 256 MiB heap, 256 KiB thread stacks, a 1 GiB container ceiling and two CPUs. Cases cover search normalization with both preservation modes and the expansion profile. They include unchanged and maximum-length input, repeated confusables, multilingual text, malformed/oversized input, combining marks, all-ignorables, high normalization expansion, abandoned output and recovery.

After each phase, workers close, borrowed workspace must be zero, reservations must stay within 2 MiB, test-only GC runs, and post-GC live heap must remain under 64 MiB. The fixed retention envelope includes shared ICU data and JVM test overhead; it is not a measurement of plugin-only heap. The final report includes immutable runtime hash, per-phase counts/heap, budget peak, process exit and Docker OOM status. Production code never forces GC.

The JUnit allocation audit measures thread-allocated bytes against the conservative Unicode reservation on warmed ICU paths. It is complementary to the data-derived bound and allocation-path audit; it does not make the reservation a whole-node memory guarantee.
