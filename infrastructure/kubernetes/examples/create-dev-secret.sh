#!/usr/bin/env bash
# Creates `oms-secrets` in a DEV/TEST namespace with freshly generated random values. Values are generated in memory and
# streamed to kubectl; nothing is written to disk or printed. Usage: create-dev-secret.sh oms-dev
# Not executed during authoring (no cluster). Requires kubectl + openssl (or node as fallback).
set -euo pipefail
NS="${1:?usage: $0 <namespace>}"
rnd() { if command -v openssl >/dev/null 2>&1; then openssl rand -hex "$1"; else node -e "console.log(require('crypto').randomBytes($1).toString('hex'))"; fi; }

kubectl -n "${NS}" create secret generic oms-secrets \
  --from-literal=jwt-secret="$(rnd 48)" \
  --from-literal=demo-password="$(rnd 12)" \
  --from-literal=postgres-password="$(rnd 24)" \
  --from-literal=order-db-username=oms_order --from-literal=order-db-password="$(rnd 24)" \
  --from-literal=inventory-db-username=oms_inventory --from-literal=inventory-db-password="$(rnd 24)" \
  --from-literal=fulfillment-db-username=oms_fulfillment --from-literal=fulfillment-db-password="$(rnd 24)" \
  --from-literal=notification-db-username=oms_notification --from-literal=notification-db-password="$(rnd 24)" \
  --from-literal=redis-password="$(rnd 24)" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "secret oms-secrets applied in namespace ${NS}"
