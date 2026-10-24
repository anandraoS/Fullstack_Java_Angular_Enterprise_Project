#!/usr/bin/env bash
# Stops everything started by scripts/run-local.sh (reads .run/*.pid). Safe to run repeatedly.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${ROOT}/.run"
[ -d "${RUN}" ] || { echo "nothing to stop (no .run directory)"; exit 0; }

# Reverse start order: gateway first, Kafka last, so consumers disconnect cleanly.
order=(serve-ui serve-ui-react api-gateway order-service notification-service fulfillment-service inventory-service local-stack)
stopped=0
for name in "${order[@]}"; do
  pf="${RUN}/${name}.pid"
  [ -f "${pf}" ] || continue
  pid="$(cat "${pf}")"
  if kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null   # SIGTERM -> Spring graceful shutdown
    for _ in $(seq 1 30); do kill -0 "${pid}" 2>/dev/null || break; sleep 1; done
    if kill -0 "${pid}" 2>/dev/null; then
      echo "${name} did not exit in 30s; forcing"
      kill -9 "${pid}" 2>/dev/null
      # Git Bash: MSYS pid may map to a Windows pid
      if [ -r "/proc/${pid}/winpid" ]; then taskkill //F //PID "$(cat "/proc/${pid}/winpid")" >/dev/null 2>&1; fi
    fi
    echo "stopped ${name} (pid ${pid})"
    stopped=$((stopped + 1))
  fi
  : >"${pf}"   # truncate; empty pid files are deleted below
done
# remove empty pid files without using rm
find "${RUN}" -maxdepth 1 -name '*.pid' -size 0 -delete 2>/dev/null
find "${RUN}" -maxdepth 1 -name 'secrets.env' -delete 2>/dev/null   # per-run secrets die with the run
echo "done (${stopped} process(es) stopped)"
