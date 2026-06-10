#!/usr/bin/env bash
# Mini-WSA end-to-end smoke. Run after `docker compose up -d` is healthy.

BASE=${BASE:-http://localhost:8080}
P=0; F=0
ok(){ echo "  ✓ $1"; P=$((P+1)); }
ko(){ echo "  ✗ $1  →  $2"; F=$((F+1)); }
hdr(){ echo; echo "── $1 ──────────────────────────"; }
status(){ curl -sS -o /dev/null -w '%{http_code}' "$@"; }
get(){ curl -sS "$@"; }

hdr "health"
[[ "$(status $BASE/actuator/health)" == "200" ]] && ok "actuator up" || { ko "actuator" "down"; exit 1; }

hdr "generator"
SC=$(get "$BASE/v1/dev/scenarios" | jq 'length')
[[ "$SC" -ge 4 ]] && ok "scenarios listed ($SC)" || ko "scenarios" "got $SC"

GEN=$(curl -sS -X POST "$BASE/v1/dev/generate?scenario=single-targeted-attack&seed=1&configId=14227")
GC=$(echo "$GEN" | jq -r .generated)
[[ "$GC" -gt 0 ]] && ok "generated $GC events" || ko "generator" "$GEN"
echo "  → waiting 6s for kafka consumer..."; sleep 6

hdr "stats — no filter"
S=$(get "$BASE/v1/stats/summary")
TOTAL=$(echo "$S" | jq -r .totalEvents)
[[ "$TOTAL" -ge "$GC" ]] && ok "totalEvents=$TOTAL" || ko "totalEvents" "got $TOTAL, expected ≥$GC"

# spec compliance — these are the risks I flagged earlier
echo "$S" | jq -e '.topAttackers[0] | has("avgThreatScore")' >/dev/null \
  && ok "topAttackers[0] has avgThreatScore" || ko "topAttackers.avgThreatScore" "MISSING (spec violation)"
echo "$S" | jq -e '.topAttackers[0] | has("clientIp") and has("count")' >/dev/null \
  && ok "topAttackers shape (clientIp+count)" || ko "topAttackers shape" "missing fields"
echo "$S" | jq -e '.topTargetedPaths[0] | has("path") and has("count")' >/dev/null \
  && ok "topTargetedPaths shape" || ko "topTargetedPaths shape" "missing fields"
echo "$S" | jq -e '.byCategory | to_entries[0].value | has("count") and has("avgThreatScore")' >/dev/null \
  && ok "byCategory has count+avgThreatScore" || ko "byCategory shape" "missing fields"
echo "$S" | jq -e '.byAction | (has("DENY") or has("ALERT") or has("MONITOR"))' >/dev/null \
  && ok "byAction populated" || ko "byAction" "empty"
echo "$S" | jq -e '.timeRange | has("from") and has("to")' >/dev/null \
  && ok "timeRange echoed" || ko "timeRange" "missing in response"

hdr "stats — filters"
T1=$(get "$BASE/v1/stats/summary?configId=14227" | jq -r .totalEvents)
[[ "$T1" -gt 0 ]] && ok "configId=14227 → $T1" || ko "configId filter" "got $T1"
T2=$(get "$BASE/v1/stats/summary?configId=99999" | jq -r .totalEvents)
[[ "$T2" == "0" ]] && ok "configId=99999 → 0" || ko "configId isolation" "got $T2"
FROM=$(date -u -d '24 hours ago' '+%Y-%m-%dT%H:%M:%SZ')
TO=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
T3=$(get "$BASE/v1/stats/summary?from=$FROM&to=$TO" | jq -r .totalEvents)
[[ "$T3" -gt 0 ]] && ok "time range filter → $T3" || ko "time range" "got $T3"

hdr "samples"
DEF=$(get "$BASE/v1/events/samples")
DLIM=$(echo "$DEF" | jq -r .limit)
[[ "$DLIM" == "20" ]] && ok "default limit=20" || ko "default limit" "got $DLIM (spec says 20)"
DCNT=$(echo "$DEF" | jq -r '.samples | length')
[[ "$DCNT" -le 20 ]] && ok "returned $DCNT (≤20)" || ko "default limit" "$DCNT > 20"

T0=$(echo "$DEF" | jq -r '.samples[0].timestamp')
TN=$(echo "$DEF" | jq -r '.samples[-1].timestamp')
[[ "$T0" > "$TN" || "$T0" == "$TN" ]] && ok "sorted timestamp DESC" || ko "sort order" "$T0 < $TN"

N5=$(get "$BASE/v1/events/samples?limit=5" | jq -r '.samples | length')
[[ "$N5" == "5" ]] && ok "limit=5 honored" || ko "limit=5" "got $N5"

NMAX=$(get "$BASE/v1/events/samples?limit=200" | jq -r .limit)
[[ "$NMAX" == "100" ]] && ok "limit=200 clamped to 100" || ko "limit clamp" "got $NMAX"

P0=$(get "$BASE/v1/events/samples?limit=5&offset=0" | jq -r '.samples[0].eventId')
P5=$(get "$BASE/v1/events/samples?limit=5&offset=5" | jq -r '.samples[0].eventId')
[[ -n "$P0" && "$P0" != "$P5" ]] && ok "pagination offset works" || ko "pagination" "$P0 == $P5"

CATS=$(get "$BASE/v1/events/samples?category=INJECTION&limit=50" | jq -r '.samples[].rule.category' | sort -u)
[[ "$CATS" == "INJECTION" || -z "$CATS" ]] && ok "category filter (only INJECTION)" || ko "category filter" "got: $CATS"

BAD=$(status "$BASE/v1/events/samples?category=NONSENSE")
[[ "$BAD" == "400" ]] && ok "bad enum query param → 400" || ko "bad enum" "got $BAD"

hdr "manual ingest"
read -r -d '' EV <<'JSON'
{"eventId":"e2e-001","timestamp":"2026-06-10T12:00:00Z","configId":14227,"policyId":"pol_web1",
"clientIp":"203.0.113.99","hostname":"www.example.com","path":"/api/v1/login","method":"POST","statusCode":403,
"userAgent":"e2e","rule":{"id":"950001","name":"SQL_INJECTION","message":"x","severity":"CRITICAL","category":"INJECTION"},
"action":"DENY","geoLocation":{"country":"CN","city":"Beijing"},"requestSize":1,"responseSize":1}
JSON

C1=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d "$EV")
[[ "$C1" =~ ^20[12]$ ]] && ok "single ingest → $C1" || ko "single ingest" "got $C1"

C2=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d "[$EV]")
[[ "$C2" =~ ^20[12]$ ]] && ok "batch (array) ingest → $C2" || ko "batch ingest" "got $C2"

sleep 4
PRE=$(get "$BASE/v1/stats/summary" | jq -r .totalEvents)
curl -sS -o /dev/null -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d "$EV"
sleep 4
POST=$(get "$BASE/v1/stats/summary" | jq -r .totalEvents)
[[ "$PRE" == "$POST" ]] && ok "idempotent (same eventId, total unchanged @ $PRE)" || ko "idempotency" "$PRE → $POST"

BAD1=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d '{"configId":14227}')
[[ "$BAD1" == "400" ]] && ok "missing required fields → 400" || ko "missing fields" "got $BAD1"

BAD2=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d "$(echo "$EV" | sed 's/"DENY"/"NUKE"/')")
[[ "$BAD2" == "400" ]] && ok "bad enum in body → 400" || ko "bad enum body" "got $BAD2"

BAD3=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/events/ingest" -H 'Content-Type: application/json' -d 'not json at all')
[[ "$BAD3" == "400" ]] && ok "malformed json → 400" || ko "malformed json" "got $BAD3"

echo
echo "═══════════════════════════════════════"
printf "  PASS: %d    FAIL: %d\n" "$P" "$F"
echo "═══════════════════════════════════════"
[[ "$F" -eq 0 ]] && exit 0 || exit 1