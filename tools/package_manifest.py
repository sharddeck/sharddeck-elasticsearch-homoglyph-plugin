#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Audit runtime contents, collect plugin ZIPs, and produce SHA-256 manifests and CycloneDX SBOMs."""
import argparse, datetime, hashlib, io, json, shutil, zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def sha(data):
    return hashlib.sha256(data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def properties(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line and not line.startswith("#"))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / "qa/integration/verification.json"
    verified = json.loads(evidence.read_text())["results"] if evidence.exists() else []
    artifacts = []
    for source in sorted((ROOT / "adapters").glob("*/build/*/distributions/*.zip")):
        blob = source.read_bytes()
        checksum = sha(blob)
        with zipfile.ZipFile(io.BytesIO(blob)) as archive:
            names = archive.namelist()
            for notice in ("LICENSE", "NOTICE", "SOURCE.md"):
                require(notice in names and archive.read(notice) == (ROOT / notice).read_bytes(),
                        f"Missing or outdated {notice}: {source}")
            if len(names) != len(set(names)) or any(".." in Path(name).parts or name.startswith("/") for name in names):
                raise ValueError("Unsafe/duplicate ZIP entries: " + str(source))
            descriptor_name = "stable-plugin-descriptor.properties" if "stable-plugin-descriptor.properties" in names else "plugin-descriptor.properties"
            descriptor = properties(archive.read(descriptor_name).decode())
            require(descriptor["name"] == "analysis-homoglyph", "Unexpected plugin ID")
            require("${" not in str(descriptor), "Unresolved descriptor property")
            components = []
            profiles = []
            jars = [name for name in names if name.endswith(".jar")]
            require(len(jars) == 4, f"Unexpected runtime jars: {source}: {jars}")
            for jar_name in jars:
                jar_blob = archive.read(jar_name)
                with zipfile.ZipFile(io.BytesIO(jar_blob)) as jar:
                    allowed = "com/ibm/icu/" if jar_name == "icu4j-78.1.jar" else "io/sharddeck/homoglyph/"
                    require(all(not entry.endswith(".class") or entry.startswith(allowed) for entry in jar.namelist()),
                            "Unexpected runtime class namespace in " + jar_name)
                    identity_path = "META-INF/homoglyph/component.properties"
                    if identity_path in jar.namelist():
                        identity = properties(jar.read(identity_path).decode())
                        group, name, version = identity["groupId"], identity["artifactId"], identity["version"]
                        require(identity.get("license") == "MPL-2.0", "Missing project license: " + jar_name)
                        for notice in ("LICENSE", "NOTICE", "SOURCE.md"):
                            require(jar.read("META-INF/" + notice) == (ROOT / notice).read_bytes(),
                                    f"Outdated {notice}: {jar_name}")
                        source_prefix = f"META-INF/homoglyph/sources/{name}/"
                        require(any(entry.startswith(source_prefix) and not entry.endswith("/") for entry in jar.namelist()),
                                "Corresponding source missing: " + jar_name)
                    elif jar_name == "icu4j-78.1.jar":
                        group, name, version = "com.ibm.icu", "icu4j", "78.1"
                    else:
                        raise ValueError("Missing artifact identity: " + jar_name)
                    component = {"type": "library", "group": group, "name": name, "version": version,
                                 "purl": f"pkg:maven/{group}/{name}@{version}",
                                 "hashes": [{"alg": "SHA-256", "content": sha(jar_blob)}]}
                    if name == "icu4j":
                        require(sha(jar_blob) == "bbb70d3be23110d7295823eee0c2e896ac3b619b3c0f26168f65eb972df51d2a", "ICU dependency checksum mismatch")
                        component["licenses"] = [{"license": {"id": "Unicode-3.0"}}]
                    else:
                        component["licenses"] = [{"license": {"id": "MPL-2.0"}}]
                    if name == "homoglyph-data":
                        prefix = "io/sharddeck/homoglyph/data/"
                        profiles = json.loads(jar.read(prefix + "profiles.json"))["profiles"]
                        require([profile["id"] for profile in profiles] == ["unicode_search_v1", "unicode_expand_v1"], "Unexpected profile inventory")
                        require({entry[len(prefix):] for entry in jar.namelist() if entry.startswith(prefix) and not entry.endswith("/")} ==
                                {"profiles.json", "unicode-bounds.bin", "unicode-expansions.bin"}, "Unexpected data resource inventory")
                        for profile in profiles:
                            canonical = {key: value for key, value in profile.items() if key != "fingerprint_sha256"}
                            require(sha(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()) == profile["fingerprint_sha256"], "Invalid profile fingerprint")
                            resource, digest = ("unicode-bounds.bin", "bounds_sha256") if profile["id"] == "unicode_search_v1" else ("unicode-expansions.bin", "expansions_sha256")
                            require(sha(jar.read(prefix + resource)) == profile[digest], "Unicode resource checksum mismatch")
                    components.append(component)
            require(any("licenses/icu/" in name for name in names), "ICU notice missing")
            if descriptor_name.startswith("stable"):
                named = json.loads(archive.read("named_components.json"))
                require("org.elasticsearch.plugin.analysis.TokenFilterFactory" in named, "Missing stable component registration")
        destination = args.output / source.name
        shutil.copyfile(source, destination)
        sbom = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
                "metadata": {"component": {"type": "application", "name": descriptor["name"], "version": descriptor["version"],
                                           "licenses": [{"license": {"id": "MPL-2.0"}}]}},
                "components": components}
        (args.output / (source.name + ".cdx.json")).write_text(json.dumps(sbom, indent=2) + "\n")
        artifacts.append({"file": source.name, "sha256": checksum, "descriptor": descriptor,
                          "verified_engines": [r["version"] for r in verified if r.get("status") == "passed" and r.get("artifact_sha256") == checksum],
                          "runtime_components": components, "profiles": profiles})
    if not artifacts:
        parser.error("No plugin ZIPs; run ./gradlew build first")
    (args.output / "SHA256SUMS").write_text("".join(f"{a['sha256']}  {a['file']}\n" for a in artifacts))
    (args.output / "manifest.json").write_text(json.dumps({"generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(), "artifacts": artifacts}, indent=2) + "\n")
    print(f"Audited and collected {len(artifacts)} plugin packages in {args.output}")

if __name__ == "__main__":
    main()
