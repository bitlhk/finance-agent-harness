#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
repo="${FH_MAVEN_REPO:-$root/target/m2-agentscope}"
run="${1:-${FH_PROBE_OUTPUT:-$root/target/agentscope-probe-$(date -u +%Y%m%dT%H%M%SZ)}}"
repo="$(realpath -m "$repo")"
run="$(realpath -m "$run")"
test ! -e "$run"
mkdir -p "$run/raw" "$run/lib" "$run/host-classes"
cd "$root"
# The parent target holds the default private cache/output and must survive clean.
mvn -B -Dmaven.repo.local="$repo" \
  -pl :finance-harness-core,:finance-harness-assembly,:finance-harness-cashflow,:finance-provider-non-ai-banking,:finance-harness-agentscope-java \
  clean -l "$run/clean.log"
mvn -B -Dmaven.repo.local="$repo" -pl integrations/agentscope-java -am install \
  -Dagentscope.evidenceDirectory="$run/raw" -l "$run/maven.log"
mvn -B -Dmaven.repo.local="$repo" -pl integrations/agentscope-java dependency:copy-dependencies \
  -DincludeScope=test -DexcludeGroupIds=org.junit.jupiter,org.junit.platform,org.opentest4j,org.apiguardian \
  -DexcludeArtifactIds=slf4j-simple -DoutputDirectory="$run/lib" -l "$run/dependencies.log"
mvn -B -Dmaven.repo.local="$repo" -pl integrations/agentscope-java dependency:tree \
  -Dscope=runtime -DoutputFile="$run/dependency-tree.txt" -l "$run/dependency-tree.log"
if rg -i openjiuwen "$run/dependency-tree.txt"; then exit 1; fi
cp integrations/agentscope-java/target/finance-harness-agentscope-java-0.1.0-SNAPSHOT.jar "$run/lib/"
cp -a integrations/agentscope-java/target/test-classes "$run/test-classes"
classpath="$run/lib/*:$run/test-classes:$run/host-classes"
javac --release 21 -cp "$classpath" -d "$run/host-classes" integrations/agentscope-java/isolatedHost/*.java
java -cp "$classpath" io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.QualificationWorker "$root" "$run/raw" "$run/qualification"
digest="$(tr -d '\n' < "$run/qualification/qualification.sha256")"
java -cp "$classpath" io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.IsolatedHost controlled "$root" \
  "$run/qualification/agentscope-observation-qualification.json" "$digest" "$run/controlled-host"
java -cp "$classpath" io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.IsolatedHost controlled "$root" \
  "$run/qualification/agentscope-observation-qualification.json" "$digest" "$run/controlled-host-second-jvm"
printf '%s\n' "$classpath" > "$run/classpath.txt"
printf 'Controlled qualification and isolated Host passed: %s\n' "$run"
