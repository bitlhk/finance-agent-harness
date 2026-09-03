#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../../.." && pwd)"
conversation="${1:-demo/balance-query.txt}"
if [[ "$conversation" != /* ]]; then
  conversation="$script_dir/$conversation"
fi

if [[ -z "${FINANCE_AGENT_MODEL_API_KEY:-}" ]]; then
  echo "FINANCE_AGENT_MODEL_API_KEY is required" >&2
  exit 2
fi

mvn -q -f "$repository_root/pom.xml" \
  -pl examples/solutions/openjiuwen-retail-banking \
  -am install -DskipTests

exec mvn -q -f "$script_dir/pom.xml" \
  exec:java -Dexec.args="--script=$conversation"
