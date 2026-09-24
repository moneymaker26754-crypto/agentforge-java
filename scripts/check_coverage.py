#!/usr/bin/env python3
"""Enforce repository-level JaCoCo thresholds after `mvn verify`."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def counters(report: Path) -> dict[str, tuple[int, int]]:
    root = ET.parse(report).getroot()
    return {
        node.attrib["type"]: (int(node.attrib["missed"]), int(node.attrib["covered"]))
        for node in root.findall("counter")
    }


reports = sorted(Path(".").glob("agentforge-*/target/site/jacoco/jacoco.xml"))
if len(reports) != 4:
    raise SystemExit(f"expected 4 JaCoCo reports, found {len(reports)}; run ./mvnw verify first")

by_module = {report.parts[0]: counters(report) for report in reports}
core = by_module["agentforge-core"]
core_line = core["LINE"][1] / sum(core["LINE"])
core_branch = core["BRANCH"][1] / sum(core["BRANCH"])

line_missed = sum(value["LINE"][0] for value in by_module.values())
line_covered = sum(value["LINE"][1] for value in by_module.values())
overall_line = line_covered / (line_missed + line_covered)

print(f"core line={core_line:.2%}, core branch={core_branch:.2%}, overall line={overall_line:.2%}")
failed = core_line < 0.85 or core_branch < 0.75 or overall_line < 0.75
sys.exit(1 if failed else 0)
