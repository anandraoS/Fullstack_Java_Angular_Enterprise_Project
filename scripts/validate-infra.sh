#!/usr/bin/env bash
# Validates infrastructure, CI, observability and performance assets with whatever tools are available.
#   node scripts/fetch-validation-tools.mjs kustomize kubeconform terraform   # optional, checksum-verified downloads into .work/tools
#   bash scripts/validate-infra.sh
#
# Each check prints PASS, FAIL or SKIP. SKIP states why and is NOT a pass; the summary counts them separately and the
# script never reports success for something it did not run. Exit code 1 when at least one check FAILED.
# Tools are looked up on PATH first, then under .work/tools/<tool>/.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA="${ROOT}/infrastructure"
TMP="$(mktemp -d)"
RESULTS=()
FAILS=0; PASSES=0; SKIPS=0

cleanup() {
  find "${TMP}" -type f -delete 2>/dev/null
  find "${TMP}" -depth -type d -empty -delete 2>/dev/null
}
trap cleanup EXIT

record() { # status name detail
  RESULTS+=("$1|$2")
  case "$1" in PASS) PASSES=$((PASSES + 1)) ;; FAIL) FAILS=$((FAILS + 1)) ;; SKIP) SKIPS=$((SKIPS + 1)) ;; esac
  printf '%-5s %s %s\n' "$1" "$2" "${3:+- $3}"
}

find_tool() { # name -> path
  local name="$1" path
  if path="$(command -v "${name}" 2>/dev/null)"; then printf '%s' "${path}"; return 0; fi
  if [ -d "${ROOT}/.work/tools/${name}" ]; then
    path="$(find "${ROOT}/.work/tools/${name}" -maxdepth 3 -type f \( -name "${name}" -o -name "${name}.exe" \) 2>/dev/null | head -n 1)"
    if [ -n "${path}" ]; then printf '%s' "${path}"; return 0; fi
  fi
  return 1
}

run_check() { # name, command...
  local name="$1"; shift
  local out="${TMP}/out.txt"
  if "$@" >"${out}" 2>&1; then
    record PASS "${name}" "$(tail -n 1 "${out}" | cut -c1-110)"
  else
    record FAIL "${name}" "see output below"
    sed 's/^/      | /' "${out}" | tail -n 25
  fi
}

PY="$(find_tool python || find_tool python3 || true)"
NODE="$(find_tool node || true)"

# ------------------------------------------------------------------ 1. scripts, JSON, YAML, JS syntax
for f in "${ROOT}"/scripts/*.sh "${INFRA}"/compose/*/*.sh "${INFRA}"/compose/postgres/init/*.sh "${INFRA}"/kubernetes/examples/*.sh; do
  [ -f "${f}" ] || continue
  run_check "bash -n ${f#"${ROOT}/"}" bash -n "${f}"
done

if [ -n "${NODE}" ]; then
  for f in "${ROOT}"/scripts/*.mjs "${ROOT}"/scripts/lib/*.mjs "${ROOT}"/observability/grafana/*.mjs "${ROOT}"/performance/*.js "${ROOT}"/performance/lib/*.js; do
    [ -f "${f}" ] || continue
    case "${f}" in
      */performance/*) # k6 scripts are ES modules using k6 built-ins: node --check validates syntax only (copy as .mjs)
        cp "${f}" "${TMP}/$(basename "${f}").mjs"
        run_check "node --check ${f#"${ROOT}/"} (syntax only)" "${NODE}" --check "${TMP}/$(basename "${f}").mjs" ;;
      *) run_check "node --check ${f#"${ROOT}/"}" "${NODE}" --check "${f}" ;;
    esac
  done
else
  record SKIP "node --check" "node not found"
fi

if [ -n "${PY}" ]; then
  if "${PY}" -I -c 'import yaml' 2>/dev/null; then
    run_check "YAML parse (infrastructure, observability, workflow)" "${PY}" -I -c '
import os, sys, yaml
root = sys.argv[1]
n = 0
for base in ("infrastructure", "observability", ".github"):
    for dirpath, dirs, names in os.walk(os.path.join(root, base)):
        dirs[:] = [d for d in dirs if d != ".terraform"]
        for name in names:
            if name.endswith((".yml", ".yaml")):
                with open(os.path.join(dirpath, name), encoding="utf-8") as fh:
                    list(yaml.safe_load_all(fh)); n += 1
print("parsed %d yaml files" % n)
' "${ROOT}"
    run_check "observability structure (dashboards, alert rules)" "${PY}" -I "${ROOT}/scripts/check-observability.py"
    run_check "compose files: services, depends_on targets, no inline secrets" "${PY}" -I -c '
import sys, re, yaml
root = sys.argv[1]
files = ["infrastructure/compose/docker-compose.yml", "infrastructure/compose/docker-compose.observability.yml"]
base = yaml.safe_load(open(root + "/" + files[0], encoding="utf-8"))
svc = base["services"]
bad = []
for f in files:
    d = yaml.safe_load(open(root + "/" + f, encoding="utf-8"))
    for name, s in d["services"].items():
        deps = s.get("depends_on", {})
        for dep in (deps if isinstance(deps, list) else deps.keys()):
            if dep not in svc and dep not in d["services"]:
                bad.append("%s: %s depends_on unknown %s" % (f, name, dep))
text = open(root + "/infrastructure/compose/docker-compose.yml", encoding="utf-8").read()
for m in re.finditer(r"(PASSWORD|SECRET)\w*:[ 	]+(?![ 	]|\$\{)(\S+)", text):
    bad.append("possible inline secret: " + m.group(0))
print("services: %d, problems: %d" % (len(svc), len(bad)))
sys.exit(1 if bad else 0)
' "${ROOT}"
  else
    record SKIP "YAML/observability/compose structure checks" "PyYAML not installed (pip install pyyaml)"
  fi
else
  record SKIP "python-based checks" "python not found"
fi

# ------------------------------------------------------------------ 2. Docker (not installed on the author machine)
COMPOSE_CMD=()
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then COMPOSE_CMD=(docker compose); fi
if [ "${#COMPOSE_CMD[@]}" -gt 0 ]; then
  CENV="${TMP}/compose.env"
  # throwaway placeholder values, only so `config` can interpolate; never used to run anything
  for v in OMS_JWT_SECRET OMS_DEMO_PASSWORD POSTGRES_PASSWORD ORDER_DB_PASSWORD INVENTORY_DB_PASSWORD FULFILLMENT_DB_PASSWORD NOTIFICATION_DB_PASSWORD REDIS_PASSWORD GRAFANA_ADMIN_PASSWORD; do
    echo "${v}=validate-only-placeholder" >>"${CENV}"
  done
  run_check "docker compose config (base)" "${COMPOSE_CMD[@]}" --env-file "${CENV}" -f "${INFRA}/compose/docker-compose.yml" config -q
  run_check "docker compose config (base + observability)" "${COMPOSE_CMD[@]}" --env-file "${CENV}" -f "${INFRA}/compose/docker-compose.yml" -f "${INFRA}/compose/docker-compose.observability.yml" config -q
else
  record SKIP "docker compose config" "docker not installed; compose files and image builds were NOT executed or schema-validated by Docker"
fi

# ------------------------------------------------------------------ 3. Kustomize + kubeconform
KUSTOMIZE="$(find_tool kustomize || true)"
KUBECTL="$(find_tool kubectl || true)"
KUBECONFORM="$(find_tool kubeconform || true)"
render() { # dir out
  if [ -n "${KUSTOMIZE}" ]; then "${KUSTOMIZE}" build "$1" >"$2"; else "${KUBECTL}" kustomize "$1" >"$2"; fi
}
RENDERED=()
if [ -n "${KUSTOMIZE}" ] || [ -n "${KUBECTL}" ]; then
  for target in overlays/dev overlays/test overlays/prod monitoring; do
    out="${TMP}/$(echo "${target}" | tr '/' '-').yaml"
    if render "${INFRA}/kubernetes/${target}" "${out}" 2>"${TMP}/err.txt"; then
      record PASS "kustomize build ${target}" "$(grep -c '^kind:' "${out}") objects"
      RENDERED+=("${out}")
    else
      record FAIL "kustomize build ${target}" "see output below"
      sed 's/^/      | /' "${TMP}/err.txt" | tail -n 15
    fi
  done
else
  record SKIP "kustomize build" "neither kustomize nor kubectl found (node scripts/fetch-validation-tools.mjs kustomize)"
fi
if [ -n "${KUBECONFORM}" ] && [ "${#RENDERED[@]}" -gt 0 ]; then
  for f in "${RENDERED[@]}"; do
    # -ignore-missing-schemas: CRDs (ServiceMonitor, PrometheusRule) have no built-in schema; core kinds ARE validated.
    # Needs network the first time (schemas are fetched from the kubeconform schema repository).
    run_check "kubeconform -strict $(basename "${f}")" "${KUBECONFORM}" -strict -ignore-missing-schemas -summary "${f}"
  done
else
  record SKIP "kubeconform" "kubeconform missing or nothing rendered (node scripts/fetch-validation-tools.mjs kubeconform)"
fi
if [ "${#RENDERED[@]}" -gt 0 ] && [ -n "${PY}" ] && "${PY}" -I -c 'import yaml' 2>/dev/null; then
  run_check "policy: non-root, readOnlyRootFilesystem, probes, limits, secretKeyRef-only (rendered overlays)" "${PY}" -I -c '
import sys, yaml
bad = []
for f in sys.argv[1:]:
    for d in yaml.safe_load_all(open(f, encoding="utf-8")):
        if not d: continue
        if d["kind"] == "Secret": bad.append(f + ": Secret object rendered (secrets must be out-of-band)")
        if d["kind"] in ("Deployment", "StatefulSet"):
            name = d["metadata"]["name"]
            spec = d["spec"]["template"]["spec"]
            managed_db = name in ("postgres",)
            for c in spec["containers"]:
                sc = c.get("securityContext", {})
                if sc.get("allowPrivilegeEscalation") is not False: bad.append(name + ": allowPrivilegeEscalation not false")
                if not managed_db and name not in ("kafka",) and sc.get("readOnlyRootFilesystem") is not True: bad.append(name + ": rootfs not read-only")
                if "resources" not in c or "limits" not in c["resources"]: bad.append(name + ": no limits")
                if name in ("order-service","inventory-service","fulfillment-service","notification-service","api-gateway"):
                    for p in ("startupProbe","readinessProbe","livenessProbe"):
                        if p not in c: bad.append(name + ": missing " + p)
                for e in c.get("env", []):
                    if e["name"].endswith(("PASSWORD","SECRET")) and "valueFrom" not in e: bad.append(name + ": literal " + e["name"])
            if not (spec.get("securityContext", {}).get("runAsNonRoot")): bad.append(name + ": runAsNonRoot not set")
print("problems: %d" % len(bad))
print("\n".join(sorted(set(bad))))
sys.exit(1 if bad else 0)
' "${RENDERED[@]}"
fi

# ------------------------------------------------------------------ 4. Terraform
TF="$(find_tool terraform || true)"
TFDIR="${INFRA}/cloud/aws/terraform"
if [ -n "${TF}" ]; then
  run_check "terraform fmt -check" "${TF}" -chdir="${TFDIR}" fmt -check -recursive
  if "${TF}" -chdir="${TFDIR}" init -backend=false -input=false >"${TMP}/tf-init.txt" 2>&1; then
    run_check "terraform validate (no plan/apply, no credentials)" "${TF}" -chdir="${TFDIR}" validate
  else
    record SKIP "terraform validate" "terraform init failed (provider download needs network); NOT validated"
    sed 's/^/      | /' "${TMP}/tf-init.txt" | tail -n 6
  fi
else
  record SKIP "terraform fmt/validate" "terraform not found (node scripts/fetch-validation-tools.mjs terraform)"
fi

# ------------------------------------------------------------------ 5. secrets (best effort)
GITLEAKS="$(find_tool gitleaks || true)"
if [ -n "${GITLEAKS}" ]; then
  run_check "gitleaks (working tree)" "${GITLEAKS}" detect --no-git --source "${ROOT}" --redact --no-banner
else
  if [ -n "${PY}" ]; then
    run_check "secret-pattern grep (weak substitute for gitleaks)" "${PY}" -I -c '
import os, re, sys
root = sys.argv[1]
pats = [re.compile(p) for p in (r"-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----", r"AKIA[0-9A-Z]{16}", r"ghp_[A-Za-z0-9]{36}", r"xox[baprs]-[A-Za-z0-9-]{10,}")]
skip = {"node_modules", "target", "dist", ".angular", ".run", ".work", ".git", ".terraform", ".claude"}
hits = []
for d, dirs, files in os.walk(root):
    dirs[:] = [x for x in dirs if x not in skip]
    for f in files:
        if f.endswith((".zip", ".png", ".jpg", ".pdf")): continue
        p = os.path.join(d, f)
        try: t = open(p, encoding="utf-8").read()
        except Exception: continue
        for r in pats:
            if r.search(t): hits.append(p + " ~ " + r.pattern)
print("matches: %d" % len(hits)); print("\n".join(hits))
sys.exit(1 if hits else 0)
' "${ROOT}"
  fi
  record SKIP "gitleaks" "binary not found (node scripts/fetch-validation-tools.mjs gitleaks); CI runs the real scan"
fi

# ------------------------------------------------------------------ summary
echo
echo "================ summary ================"
for r in "${RESULTS[@]}"; do IFS='|' read -r status name <<<"${r}"; printf '%-5s %s\n' "${status}" "${name}"; done
echo "-----------------------------------------"
echo "PASS=${PASSES} FAIL=${FAILS} SKIP=${SKIPS}  (SKIP means NOT validated)"
[ "${FAILS}" -eq 0 ]
