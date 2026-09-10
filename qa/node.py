# SPDX-License-Identifier: MPL-2.0
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Disposable local QA nodes. Never connects to or removes an existing user cluster."""
import http.client, json, shutil, subprocess, tempfile, time
from pathlib import Path

class Node:
    def __init__(self, name, image, artifact, icu=False, java11=False):
        self.name, self.image, self.artifact, self.icu = name, image, artifact, icu
        self.java11 = java11
        self.host = "opensearch" if "opensearchproject/" in image else "elasticsearch"
        self.container = None

    def __enter__(self):
        with tempfile.TemporaryDirectory(prefix="homoglyph-node-") as directory:
            path = Path(directory)
            shutil.copyfile(self.artifact, path / "plugin.zip")
            install = f"/usr/share/{self.host}/bin/{self.host}-plugin install --batch "
            (path / "Dockerfile").write_text(
                ("FROM eclipse-temurin:11-jdk AS java11\n" if self.java11 else "") +
                f"FROM {self.image}\n" +
                ("COPY --from=java11 /opt/java/openjdk /opt/java/openjdk\nENV ES_JAVA_HOME=/opt/java/openjdk\n" if self.java11 else "") +
                "COPY --chown=1000:0 plugin.zip /tmp/homoglyph.zip\nRUN " +
                install + "file:///tmp/homoglyph.zip && rm /tmp/homoglyph.zip" +
                (" && " + install + "analysis-icu" if self.icu else "") + "\n")
            subprocess.run(["docker", "build", "--quiet", "--tag", self.name, directory], check=True)
        environment = {"discovery.type": "single-node", "node.store.allow_mmap": "false",
                       "cluster.name": self.name,
                       "OPENSEARCH_JAVA_OPTS" if self.host == "opensearch" else "ES_JAVA_OPTS": "-Xms512m -Xmx512m"}
        if self.host == "opensearch":
            environment.update(DISABLE_INSTALL_DEMO_CONFIG="true", DISABLE_SECURITY_PLUGIN="true")
        else:
            environment.update({"xpack.security.enabled": "false", "xpack.ml.enabled": "false", "ingest.geoip.downloader.enabled": "false"})
        command = ["docker", "run", "--detach", "--name", self.name, "--label", "io.sharddeck.homoglyph.qa=true",
                   "--memory", "2g", "--memory-swap", "2g", "--cpus", "2", "--pids-limit", "512", "--publish", "127.0.0.1::9200"]
        for key, value in environment.items():
            command.extend(["--env", key + "=" + value])
        self.container = subprocess.check_output([*command, self.name], text=True).strip()
        try:
            self.state = self.inspect()
            self.port = int(self.state["NetworkSettings"]["Ports"]["9200/tcp"][0]["HostPort"])
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                try:
                    code, health = self.request("/_cluster/health")
                    if code == 200 and health["status"] in ("green", "yellow"):
                        return self
                except (OSError, ValueError, http.client.HTTPException):
                    pass
                time.sleep(2)
            raise RuntimeError("Node startup timed out")
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def request(self, path, body=None, method="GET", content_type="application/json", connection=None):
        owned = connection is None
        if owned:
            connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
        try:
            data = body if isinstance(body, str) else json.dumps(body) if body is not None else None
            connection.request(method, path, data.encode("utf-8") if data is not None else None, {"Content-Type": content_type})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            if owned:
                connection.close()

    def inspect(self):
        return json.loads(subprocess.check_output(["docker", "inspect", self.container], text=True))[0]

    def __exit__(self, *unused):
        if self.container is not None:
            subprocess.run(["docker", "stop", "--timeout", "60", self.container], check=True, stdout=subprocess.DEVNULL)
            self.state = self.inspect()
            self.logs = subprocess.check_output(["docker", "logs", "--tail", "100", self.container], text=True, stderr=subprocess.STDOUT)
            subprocess.run(["docker", "rm", self.container], check=True, stdout=subprocess.DEVNULL)
            self.container = None
