#!/usr/bin/env bash
# Starts the whole OMS locally WITHOUT Docker: embedded Kafka (local-stack), 4 services and the gateway as plain JVMs.
#
#   scripts/run-local.sh [--no-build] [--emit-env FILE]
#
#   --no-build        fail instead of running Maven when a jar is missing
#   --json-logs       add profile json-logs (structured JSON log lines with correlationId) instead of the plain console pattern
#   --ui [angular|react|both]
#                     also serve a BUILT UI via scripts/serve-ui.mjs with /api proxied to the gateway: Angular on :4200
#                     (default when no value is given), React on :4201, or both. Build first (npx ng build | npm run build).
#   --emit-env FILE   write OMS_DEMO_PASSWORD/OMS_BASE_URL to FILE (mode 600) instead of printing the password
#                     (used by CI; keep FILE outside the repository)
#
# Java: uses $OMS_JAVA_HOME (default below) by full path. JAVA_HOME is NOT exported or modified globally.
# Secrets: OMS_JWT_SECRET and OMS_DEMO_PASSWORD are generated randomly per run, passed to the child JVMs via their
# environment only (not on the command line), and the demo password is printed ONCE at the end. Nothing is hard-coded.
# State: pid files and logs under .run/ (git-ignored). Stop with scripts/stop-local.sh.
# Ports (contract section 2): gateway 18080 (override: OMS_GATEWAY_PORT), order 18081, inventory 18082, fulfillment 18083, notification 18084, Kafka 19092.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${ROOT}/.run"
OMS_JAVA_HOME="${OMS_JAVA_HOME:-C:/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12}"
OMS_MVN="${OMS_MVN:-${HOME}/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn}"
# The gateway port is overridable when a FOREIGN process (another project) holds 18080:
#   OMS_GATEWAY_PORT=18085 bash scripts/run-local.sh --no-build --ui react
GATEWAY_PORT="${OMS_GATEWAY_PORT:-18080}"
NO_BUILD=0
JSON_LOGS=0
SERVE_UI=0   # 0 | angular | react | both
EMIT_ENV=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) NO_BUILD=1 ;;
    --json-logs) JSON_LOGS=1 ;;
    --ui)
      SERVE_UI=angular   # bare --ui keeps the old meaning (Angular on :4200)
      case "${2:-}" in angular|react|both) SERVE_UI="$2"; shift ;; esac ;;
    --emit-env) shift; EMIT_ENV="${1:?--emit-env needs a file}" ;;
    -h|--help) sed -n 2,20p "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

JAVA="${OMS_JAVA_HOME}/bin/java"
[ -x "${JAVA}" ] || JAVA="${OMS_JAVA_HOME}/bin/java.exe"
if [ ! -x "${JAVA}" ]; then
  echo "Java 17 not found under OMS_JAVA_HOME='${OMS_JAVA_HOME}'. Set OMS_JAVA_HOME to a JDK 17 directory." >&2
  exit 1
fi
if ! "${JAVA}" -version 2>&1 | grep -q 'version "17'; then
  echo "WARNING: ${JAVA} does not report Java 17:" >&2
  "${JAVA}" -version 2>&1 | head -n 1 >&2
fi

# name:port:jar
SERVICES=(
  "inventory-service:18082:services/inventory-service/target/inventory-service-1.0.0.jar"
  "fulfillment-service:18083:services/fulfillment-service/target/fulfillment-service-1.0.0.jar"
  "notification-service:18084:services/notification-service/target/notification-service-1.0.0.jar"
  "order-service:18081:services/order-service/target/order-service-1.0.0.jar"
  "api-gateway:${GATEWAY_PORT}:gateway/target/api-gateway-1.0.0.jar"
)
LOCAL_STACK_JAR="services/local-stack/target/local-stack-1.0.0.jar"   # assumed artifact name (module local-stack)

mkdir -p "${RUN}"
for pf in "${RUN}"/*.pid; do
  [ -f "${pf}" ] || continue
  if kill -0 "$(cat "${pf}")" 2>/dev/null; then
    echo "Already running (${pf}). Run scripts/stop-local.sh first." >&2
    exit 1
  fi
done

# ---- refuse to start when a required port is already taken (a foreign process answering on 18080 once made this script report "ready"
# for a gateway that never started)
for hp in 19092 "${GATEWAY_PORT}" 18081 18082 18083 18084; do
  if node "${ROOT}/scripts/lib/wait-for.mjs" tcp "localhost:${hp}" 1 >/dev/null 2>&1; then
    echo "Port ${hp} is already in use by another process; stop it or free the port (not started by this script: ${RUN} has no live pid for it)." >&2
    exit 1
  fi
done

# ---- build when needed
missing=0
for jar in "${LOCAL_STACK_JAR}" "${SERVICES[@]#*:*:}"; do [ -f "${ROOT}/${jar}" ] || missing=1; done
if [ "${missing}" -eq 1 ]; then
  if [ "${NO_BUILD}" -eq 1 ]; then echo "Jars missing and --no-build given." >&2; exit 1; fi
  echo "Building jars (tests skipped) ..."
  JAVA_HOME="${OMS_JAVA_HOME}" "${OMS_MVN}" -B -ntp -q -f "${ROOT}/services/pom.xml" package -DskipTests || { echo "Maven build failed" >&2; exit 1; }
fi

# ---- per-run secrets (kept in this shell's memory and the children's environment only)
gen() { node -e "console.log(require('crypto').randomBytes($1).toString('$2'))"; }
OMS_JWT_SECRET="$(gen 48 base64)"
OMS_DEMO_PASSWORD="$(gen 12 hex)"
export OMS_JWT_SECRET OMS_DEMO_PASSWORD   # exported for child processes of THIS script only
# scripts/service-ctl.sh needs the SAME secret to restart one service (resilience demo). Stored mode 600 in the git-ignored
# .run/ directory and deleted by stop-local.sh; random per run, never committed.
( umask 077; printf 'OMS_JWT_SECRET=%s\nOMS_DEMO_PASSWORD=%s\nOMS_PROFILES=%s\n' "${OMS_JWT_SECRET}" "${OMS_DEMO_PASSWORD}" "dev,local$([ "${JSON_LOGS}" -eq 1 ] && echo ',json-logs')" >"${RUN}/secrets.env" )

start() { # name jar [port]
  local name="$1" jar="$2" port="${3:-}"
  local envs=(SPRING_PROFILES_ACTIVE="dev,local$([ "${JSON_LOGS}" -eq 1 ] && echo ',json-logs')" SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 TZ=UTC)
  [ -n "${port}" ] && envs+=(SERVER_PORT="${port}")
  env "${envs[@]}" "${JAVA}" -Xmx384m -XX:+ExitOnOutOfMemoryError -jar "${ROOT}/${jar}" >"${RUN}/${name}.log" 2>&1 &
  echo $! >"${RUN}/${name}.pid"
  echo "started ${name} (pid $!, log .run/${name}.log)"
}

stop_all_on_failure() {
  echo "Startup failed; stopping what was started ..." >&2
  bash "${ROOT}/scripts/stop-local.sh" >/dev/null 2>&1
}

# ---- 1. embedded Kafka
start local-stack "${LOCAL_STACK_JAR}"
if ! node "${ROOT}/scripts/lib/wait-for.mjs" tcp localhost:19092 120; then
  echo "Embedded Kafka did not open port 19092. See .run/local-stack.log" >&2
  tail -n 30 "${RUN}/local-stack.log" >&2; stop_all_on_failure; exit 1
fi

# ---- 2. services, then gateway
for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port jar <<<"${entry}"
  start "${name}" "${jar}" "${port}"
done
for entry in "${SERVICES[@]}"; do
  IFS=: read -r name port jar <<<"${entry}"
  if ! node "${ROOT}/scripts/lib/wait-for.mjs" http "http://localhost:${port}/actuator/health/readiness" 300; then
    echo "${name} did not become ready on port ${port}. Last log lines:" >&2
    tail -n 40 "${RUN}/${name}.log" >&2; stop_all_on_failure; exit 1
  fi
  echo "ready: ${name} :${port}"
done

BASE_GW="http://localhost:${GATEWAY_PORT}"
if [ "${SERVE_UI}" = "angular" ] || [ "${SERVE_UI}" = "both" ]; then
  GATEWAY_URL="${BASE_GW}" UI=angular nohup node "${ROOT}/scripts/serve-ui.mjs" >"${RUN}/serve-ui.log" 2>&1 &
  echo $! >"${RUN}/serve-ui.pid"
  echo "started serve-ui angular (pid $!, http://localhost:4200)"
fi
if [ "${SERVE_UI}" = "react" ] || [ "${SERVE_UI}" = "both" ]; then
  GATEWAY_URL="${BASE_GW}" UI=react nohup node "${ROOT}/scripts/serve-ui.mjs" >"${RUN}/serve-ui-react.log" 2>&1 &
  echo $! >"${RUN}/serve-ui-react.pid"
  echo "started serve-ui react (pid $!, http://localhost:4201)"
fi

BASE="http://localhost:${GATEWAY_PORT}"
echo
echo "OMS is up."
echo "  Gateway API : ${BASE}/api/v1   (token: POST ${BASE}/api/v1/auth/token)"
echo "  Swagger UI  : http://localhost:18081/swagger-ui.html (order), 18082 inventory, 18083 fulfillment, 18084 notification"
echo "  Health      : http://localhost:18081/actuator/health  | metrics: /actuator/prometheus on each port"
echo "  Angular UI  : cd frontend/oms-ui && npm start  ->  http://localhost:4200 (proxies /api to 18080)"
echo "  React UI    : npm --prefix frontend/oms-ui-react start  ->  http://localhost:4201 (proxies /api to 18080)"
echo "  Logs / pids : ${RUN}   Stop: scripts/stop-local.sh"
if [ -n "${EMIT_ENV}" ]; then
  ( umask 077; printf 'OMS_DEMO_PASSWORD=%s\nOMS_BASE_URL=%s\n' "${OMS_DEMO_PASSWORD}" "${BASE}" >"${EMIT_ENV}" )
  echo "  Credentials written to ${EMIT_ENV} (mode 600)."
else
  echo
  echo "  Demo users: customer1, customer2, operator1, admin1"
  echo "  OMS_DEMO_PASSWORD (shown ONCE, random per run) = ${OMS_DEMO_PASSWORD}"
  echo "  (JWT secret is random per run and intentionally not printed.)"
fi
