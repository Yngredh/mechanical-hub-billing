#!/usr/bin/env bash
# Smoke test do mechanical-hub-billing contra uma instancia ja no ar.
#
# Uso: ci/smoke-test.sh <base-url>     (ex.: http://localhost:8080)
#
# Confere readiness (banco e migrations), que o webhook e publico e rejeita
# chamada sem assinatura valida (401) e que as metricas do outbox estao expostas.
# Nao chama o Mercado Pago.
set -euo pipefail

BASE_URL="${1:?informe a URL base, ex.: http://localhost:8080}"

fail() {
  echo "FALHOU: $*" >&2
  exit 1
}

echo "-> readiness"
readiness="$(curl -fsS --max-time 10 "$BASE_URL/actuator/health/readiness")" || fail "readiness nao respondeu"
grep -q '"status":"UP"' <<< "$readiness" || fail "readiness diferente de UP: $readiness"

echo "-> POST /webhooks/payment sem assinatura"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
  -H 'Content-Type: application/json' \
  -d '{"action":"smoke","data":{"id":"0"}}' \
  "$BASE_URL/webhooks/payment")"
[ "$code" = "401" ] || fail "webhook sem assinatura deveria ser 401, veio $code"

echo "-> /actuator/prometheus"
metrics="$(curl -fsS --max-time 10 "$BASE_URL/actuator/prometheus")" || fail "prometheus nao respondeu"
grep -q '^outbox_oldest_pending_age_seconds' <<< "$metrics" || fail "metrica outbox_oldest_pending_age_seconds ausente"

echo "Smoke test OK em $BASE_URL"
