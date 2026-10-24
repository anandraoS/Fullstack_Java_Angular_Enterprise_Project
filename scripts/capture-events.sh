#!/usr/bin/env bash
# Captures all records of the live embedded Kafka (all oms.* topics incl. DLTs) into JSON Lines, then validates them.
#   scripts/capture-events.sh [out.jsonl]      default out: .run/kafka-events.jsonl (full capture, git-ignored)
# Afterwards a trimmed sample (2 events per type, synthetic data) is written to tests/e2e/samples/kafka-events.sample.jsonl,
# which is what `node scripts/validate-events.mjs` validates by default and what is committed.
# Uses kafka-clients + slf4j-api jars from ~/.m2 (same versions the services use) and the JDK 17 single-file launcher.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OMS_JAVA_HOME="${OMS_JAVA_HOME:-C:/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12}"
KV="${KAFKA_CLIENTS_VERSION:-3.9.2}"; SV="${SLF4J_VERSION:-2.0.17}"
OUT="${1:-${ROOT}/.run/kafka-events.jsonl}"
mkdir -p "$(dirname "${OUT}")"
M2="$(cygpath -m "${HOME}" 2>/dev/null || echo "${HOME}")/.m2/repository"   # the JVM needs a Windows path, not /c/Users/...
CP="${M2}/org/apache/kafka/kafka-clients/${KV}/kafka-clients-${KV}.jar;${M2}/org/slf4j/slf4j-api/${SV}/slf4j-api-${SV}.jar"
"${OMS_JAVA_HOME}/bin/java" -cp "${CP}" "$(cygpath -m "${ROOT}" 2>/dev/null || echo "${ROOT}")/tools/capture/CaptureEvents.java" "${KAFKA_BOOTSTRAP:-localhost:19092}" "$(cygpath -m "${OUT}" 2>/dev/null || echo "${OUT}")" 2>&1 | grep -v SLF4J
node "${ROOT}/scripts/validate-events.mjs" "${OUT}"
node "${ROOT}/scripts/trim-event-samples.mjs" "${OUT}" "${ROOT}/tests/e2e/samples/kafka-events.sample.jsonl"
