#!/usr/bin/env bash
# Run before starting a release, with its own Python interpreter and fixed Harness source.
set -euo pipefail
test "$#" -eq 3 || { printf 'Usage: %s PYTHON BUILDER_MODULE HARNESS_ROOT\n' "$0" >&2; exit 2; }
python=$1
module=$2
harness=$3
test -f "$module/pyproject.toml"
test -f "$harness/engineering/requirements-conformance.txt"
"$python" -m pip install -e "$module/../base" -e "$module[test]"
"$python" -m pip install -r "$harness/engineering/requirements-conformance.txt"
"$python" -c 'from jsonschema import FormatChecker; assert not FormatChecker().conforms("not-a-date", "date-time"), "date-time validator missing"'
