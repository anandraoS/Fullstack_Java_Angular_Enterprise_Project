#!/usr/bin/env bash
# Stop / start ONE service of a stack started by scripts/run-local.sh (used by tests/e2e/resilience-live.mjs).
#   scripts/service-ctl.sh stop|start|restart <order-service|inventory-service|fulfillment-service|notification-service|api-gateway>
# A restart must use the SAME per-run JWT secret as the rest of the stack, so run-local.sh stores the per-run secrets in
# .run/secrets.env (mode 600, inside the git-ignored .run/ directory, deleted by stop-local.sh). Nothing is hard-coded.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${ROOT}/.run"
OMS_JAVA_HOME="${OMS_JAVA_HOME:-C:/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12}"
JAVA="${OMS_JAVA_HOME}/bin/java"; [ -x "${JAVA}" ] || JAVA="${OMS_JAVA_HOME}/bin/java.exe"
cmd="${1:?stop|start|restart}"; name="${2:?service name}"
case "${name}" in
  inventory-service)    port=18082; jar="services/inventory-service/target/inventory-service-1.0.0.jar" ;;
  fulfillment-service)  port=18083; jar="services/fulfillment-service/target/fulfillment-service-1.0.0.jar" ;;
  notification-service) port=18084; jar="services/notification-service/target/notification-service-1.0.0.jar" ;;
  order-service)        port=18081; jar="services/order-service/target/order-service-1.0.0.jar" ;;
  api-gateway)          port=18080; jar="gateway/target/api-gateway-1.0.0.jar" ;;
  *) echo "unknown service ${name}" >&2; exit 2 ;;
esac
pf="${RUN}/${name}.pid"

do_stop() {
  [ -s "${pf}" ] || { echo "${name}: no pid file"; return 0; }
  pid="$(cat "${pf}")"
  if kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null
    for _ in $(seq 1 30); do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
    if kill -0 "${pid}" 2>/dev/null; then
      kill -9 "${pid}" 2>/dev/null
      [ -r "/proc/${pid}/winpid" ] && taskkill //F //PID "$(cat "/proc/${pid}/winpid")" >/dev/null 2>&1
    fi
  fi
  : >"${pf}"
  echo "stopped ${name}"
}

do_start() {
  [ -f "${RUN}/secrets.env" ] || { echo "no .run/secrets.env: start the stack with scripts/run-local.sh first" >&2; exit 1; }
  if [ -s "${pf}" ] && kill -0 "$(cat "${pf}")" 2>/dev/null; then echo "${name} already running"; return 0; fi
  set -a; . "${RUN}/secrets.env"; set +a
  env SPRING_PROFILES_ACTIVE="${OMS_PROFILES:-dev,local}" SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:19092 TZ=UTC SERVER_PORT="${port}" \
    "${JAVA}" -Xmx384m -XX:+ExitOnOutOfMemoryError -jar "${ROOT}/${jar}" >>"${RUN}/${name}.log" 2>&1 &
  echo $! >"${pf}"
  if node "${ROOT}/scripts/lib/wait-for.mjs" http "http://localhost:${port}/actuator/health/readiness" 180; then echo "started ${name} (ready)"; else echo "${name} did not become ready" >&2; exit 1; fi
}

case "${cmd}" in
  stop) do_stop ;;
  start) do_start ;;
  restart) do_stop; do_start ;;
  *) echo "unknown command ${cmd}" >&2; exit 2 ;;
esac
