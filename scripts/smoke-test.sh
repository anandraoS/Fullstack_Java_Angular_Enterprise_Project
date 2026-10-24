#!/usr/bin/env bash
# Smoke test wrapper: login -> create order -> poll until FULFILLED (see smoke-test.mjs for env vars).
#   OMS_DEMO_PASSWORD=<printed by run-local.sh> scripts/smoke-test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v node >/dev/null 2>&1 || { echo "node is required" >&2; exit 2; }
exec node "${ROOT}/scripts/smoke-test.mjs" "$@"
