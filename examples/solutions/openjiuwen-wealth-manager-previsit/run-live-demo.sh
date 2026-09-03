#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../../.." && pwd)"
conversation="${1:-demo/previsit.txt}"
if [[ "$conversation" != /* ]]; then
  conversation="$script_dir/$conversation"
fi

required=(
  FINANCE_AGENT_MODEL_API_KEY
  EA_AGENT_ID
  EA_ADOPT_ID
  EA_CUSTOMER_REF
  INTERNAL_RUNTIME_TOKEN_SECRET
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "$name is required" >&2
    exit 2
  fi
done

mvn -q -f "$repository_root/pom.xml" \
  -pl examples/solutions/openjiuwen-wealth-manager-previsit \
  -am install -DskipTests

exec mvn -q -f "$script_dir/pom.xml" \
  exec:java -Dexec.args="--script=$conversation"
