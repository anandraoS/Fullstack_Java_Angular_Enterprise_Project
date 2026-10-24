"""Static structure checks for observability assets (promtool was not available). Run: python -I scripts/check-observability.py
Checks: Grafana dashboard JSON parses and every panel has targets with expr; Prometheus rule files have
groups/rules with alert+expr (+for/labels/annotations sanity), PromQL parentheses/brackets balance; YAML configs parse.
This is NOT a PromQL parser: semantic errors would only be caught by `promtool check rules`.
"""
import glob
import json
import os
import re
import sys

import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
errors = []


def balanced(expr):
    stack, pairs = [], {")": "(", "]": "[", "}": "{"}
    for ch in re.sub(r'"[^"]*"', '""', expr):
        if ch in "([{":
            stack.append(ch)
        elif ch in ")]}":
            if not stack or stack.pop() != pairs[ch]:
                return False
    return not stack


dashes = sorted(glob.glob(os.path.join(ROOT, "observability/grafana/dashboards/*.json")))
if not dashes:
    errors.append("no dashboards found")
npanels = 0
for f in dashes:
    d = json.load(open(f, encoding="utf-8"))
    if not d.get("uid") or not d.get("title"):
        errors.append(f"{f}: missing uid/title")
    for p in d.get("panels", []):
        npanels += 1
        if not p.get("targets"):
            errors.append(f"{f}: panel '{p.get('title')}' has no targets")
        for t in p.get("targets", []):
            if not t.get("expr") or not balanced(t["expr"]):
                errors.append(f"{f}: panel '{p.get('title')}' bad expr {t.get('expr')!r}")
print(f"dashboards: {len(dashes)} files, {npanels} panels")

nrules = 0
for f in sorted(glob.glob(os.path.join(ROOT, "observability/prometheus/rules/*.yml"))):
    doc = yaml.safe_load(open(f, encoding="utf-8"))
    for g in doc["groups"]:
        if not g.get("name") or not g.get("rules"):
            errors.append(f"{f}: group without name/rules")
        for r in g["rules"]:
            nrules += 1
            for k in ("alert", "expr"):
                if k not in r:
                    errors.append(f"{f}: rule missing {k}: {r}")
            if "labels" not in r or "severity" not in r["labels"]:
                errors.append(f"{f}: {r.get('alert')} missing severity label")
            if not balanced(r.get("expr", "")):
                errors.append(f"{f}: {r.get('alert')} unbalanced expr")
            if not r.get("annotations", {}).get("summary"):
                errors.append(f"{f}: {r.get('alert')} missing summary")
print(f"alert rules: {nrules}")

nyaml = 0
for pat in ("observability/**/*.yml", "observability/**/*.yaml"):
    for f in glob.glob(os.path.join(ROOT, pat), recursive=True):
        list(yaml.safe_load_all(open(f, encoding="utf-8")))
        nyaml += 1
print(f"yaml configs parsed: {nyaml}")

if errors:
    print("FAIL")
    print("\n".join(errors))
    sys.exit(1)
print("OK")
