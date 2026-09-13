"""Rebuild collector dependencies with different user/mtime inputs; compare bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


def verify(root, report):
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [root / item.text for item in ET.parse(root / "pom.xml").findall("m:modules/m:module", namespace)]
    rounds = []
    for index in range(2):
        log = report.with_suffix(".round%d.log" % index)
        with log.open("w") as output:
            subprocess.run(["mvn", "-B", "-ntp", "clean", "package", "-DskipTests",
                            "-Duser.name=reproducibility-%d" % index], cwd=root,
                           stdout=output, stderr=subprocess.STDOUT, check=True)
        jars = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                for module in modules for path in (module / "target").glob("*.jar")}
        if not jars:
            raise AssertionError("NO_JARS_CHECKED")
        rounds.append(jars)
        time.sleep(3)
    differences = sorted(key for key in rounds[0].keys() | rounds[1].keys()
                         if rounds[0].get(key) != rounds[1].get(key))
    report.write_text(json.dumps({"rounds": rounds, "differences": differences,
                                 "scope": "same JDK/Maven, clean rebuild, changed build user and time",
                                 "passed": not differences}, indent=2) + "\n")
    if differences:
        raise AssertionError("NON_REPRODUCIBLE_JARS: " + ", ".join(differences))
    print("%d JARs byte-identical across two clean builds" % len(rounds[0]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    verify(Path(__file__).resolve().parents[1], args.report.resolve())
