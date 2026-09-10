# Offline tools

Build with `./gradlew build`; the CLI uses only core, Unicode data and ICU dependencies.

```sh
java -Xmx256m -jar tools/build/libs/homoglyph-tools.jar profiles
java -Xmx256m -jar tools/build/libs/homoglyph-tools.jar validate max_input_utf16_units=1024
python3 tools/validate_config.py docs/examples/index.json
# One UTF-8 input token per line; JSON token arrays on stdout.
java -Xmx256m -jar tools/build/libs/homoglyph-tools.jar analyze preserve_original=true < tokens.txt
python3 tools/package_manifest.py
```

Validation uses the production settings parser, rejects duplicate JSON keys and checks recorded profile fingerprints. It reads at most a 1 MiB configuration and catches unknown keys ignored by the stable Elasticsearch API. Analysis rejects malformed UTF-8 and input beyond the configured UTF-16 ceiling before normalization. Neither command connects to a cluster.

Regenerate the Unicode bounds and expansion resources offline, then verify the manifest checksum:

```sh
java -Xmx256m -cp tools/build/libs/homoglyph-tools.jar tools/GenerateData.java /tmp/homoglyph-generated
```

The generator enumerates Unicode scalars with pinned ICU and performs no downloads. Profile fingerprints include pipeline order and policies. A semantic change requires a new profile ID.
