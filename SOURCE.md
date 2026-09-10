# Source availability

ShardDeck code is licensed under the Mozilla Public License 2.0 (`MPL-2.0`).
The full license is in `LICENSE` in the project and plugin ZIPs, and in
`META-INF/LICENSE` in project JARs. Third-party exceptions are listed in `NOTICE`.

Each project JAR includes the corresponding Java source and resources under
`META-INF/homoglyph/sources/<artifact-name>/`. Extract them with any ZIP utility
or the JDK's `jar` command. The data JAR also includes `GenerateData.java`, the
generator for its Unicode bounds resource.

For a plugin ZIP, first extract the ZIP, then extract the source directories
from its adapter, core and data JARs. The standalone tools and benchmark JARs
include the source directories for their bundled ShardDeck components too.
ICU and other third-party components retain their own licenses and notices.

When redistributing modified MPL-covered binaries, provide the corresponding
modified source under MPL 2.0 and inform recipients how to obtain it. The Gradle
build embeds source directly in project JARs so the source accompanies those
binaries without requiring a separate download.
