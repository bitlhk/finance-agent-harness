#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$root"
module=examples/solutions/wealth-facts-kit
# Build and test the dependency closure before running a separate JVM per scenario.
mvn -B -ntp -pl "$module" -am install
if (( $# == 0 )); then
  set -- "$module/customer-bank.json" "$module/customer-snapshot.json" "$module/employee-ea.json" \
    "$module/customer-denied.json" "$module/employee-denied.json"
fi
for configuration in "$@"; do
  mvn -B -ntp -pl "$module" exec:exec -Dexec.executable=java \
    "-Dexec.args=-classpath %classpath io.github.bitlhk.finance.harness.examples.wealthkit.WealthKitApplication \"$root/$configuration\""
done
