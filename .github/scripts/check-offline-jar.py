"""Check that the offline artifact embeds exactly the APIs its manifests request."""

import hashlib
import json
import re
import sys
from zipfile import ZipFile


def check(path):
    with ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate entries in offline jar"
        prefix = "META-INF/falsepatternlib_repo/xyz/wagyourtail/jvmdowngrader/jvmdowngrader-java-api/"
        embedded = {name for name in names if name.startswith(prefix) and name.endswith(".jar")}
        expected = set()
        for target in (8, 17):
            manifest = json.loads(archive.read(f"META-INF/gtnhextlib_deps{target}.json"))
            coordinates = [entry for entry in manifest["dependencies"]["always"]["common"]
                           if entry.startswith("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:")]
            assert len(coordinates) == 1, f"Expected one Java {target} API coordinate"
            group, artifact, version, classifier = coordinates[0].split(":")
            assert re.fullmatch(f"downgraded-{target}-gtnh-min-[0-9a-f]{{64}}", classifier), classifier
            name = f"META-INF/falsepatternlib_repo/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}-{classifier}.jar"
            expected.add(name)
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            assert classifier.endswith(digest), f"Java {target} cache identity does not match embedded bytes"
        assert embedded == expected, "Unexpected full or stale minimized APIs in offline jar"
    print(f"Offline dependency identities verified: {path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check-offline-jar.py path/to/gtnhextlib-<version>-gtnh.jar")
    check(sys.argv[1])
