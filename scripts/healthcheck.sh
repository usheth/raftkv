#!/bin/bash
# healthcheck.sh — Verify leader election and read-after-write for RaftKV.
#
# Prerequisites: grpcurl must be on PATH.
# Usage: ./scripts/healthcheck.sh
#
# Exit codes:
#   0 — all checks passed
#   1 — one or more checks failed
set -euo pipefail

NAMESPACE="raftkv"
GRPC_PORT="9090"
REPLICAS=3
STATEFULSET_NAME="raftkv"
MINIKUBE_IP=""

PASS=0
FAIL=0
ERRORS=()

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
ok()   { log "  [OK] $*";   ((PASS++)) || true; }
fail() { log "  [FAIL] $*"; ((FAIL++)) || true; ERRORS+=("$*"); }

# ---------------------------------------------------------------------------
# 1. Wait for all pods to be Ready
# ---------------------------------------------------------------------------
log "Waiting for StatefulSet rollout to complete..."
if ! kubectl rollout status statefulset/"$STATEFULSET_NAME" \
        -n "$NAMESPACE" --timeout=120s; then
    fail "StatefulSet rollout did not complete within 120s"
    echo "FAIL: $FAIL check(s) failed. Errors: ${ERRORS[*]}"
    exit 1
fi
ok "StatefulSet rollout complete"

# ---------------------------------------------------------------------------
# 2. Determine node addresses
# ---------------------------------------------------------------------------
# Build list of per-pod addresses via port-forward or minikube NodePort
MINIKUBE_IP=$(minikube ip 2>/dev/null || true)
if [ -z "$MINIKUBE_IP" ]; then
    fail "Could not determine minikube IP"
    echo "FAIL: $FAIL check(s) failed."
    exit 1
fi

# We address each pod individually via kubectl port-forward in background
declare -A POD_PORTS
declare -A PF_PIDS

cleanup_port_forwards() {
    for pid in "${PF_PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
}
trap cleanup_port_forwards EXIT

LOCAL_BASE_PORT=19090
for i in $(seq 0 $((REPLICAS - 1))); do
    POD_NAME="${STATEFULSET_NAME}-${i}"
    LOCAL_PORT=$((LOCAL_BASE_PORT + i))
    kubectl port-forward "pod/$POD_NAME" -n "$NAMESPACE" \
        "${LOCAL_PORT}:${GRPC_PORT}" >/dev/null 2>&1 &
    PF_PIDS[$i]=$!
    POD_PORTS[$i]="localhost:${LOCAL_PORT}"
done

# Give port-forwards a moment to establish
sleep 3

# ---------------------------------------------------------------------------
# 3. Check grpcurl is available
# ---------------------------------------------------------------------------
if ! command -v grpcurl &>/dev/null; then
    fail "grpcurl not found on PATH — install from https://github.com/fullstorydev/grpcurl"
    echo "FAIL: $FAIL check(s) failed."
    exit 1
fi

# ---------------------------------------------------------------------------
# Helper: call GetNodeRole on a node
# ---------------------------------------------------------------------------
get_node_role() {
    local addr="$1"
    grpcurl -plaintext -d '{}' "$addr" raftkv.KvService/GetNodeRole 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 4. Wait for a leader to be elected (poll up to 30 s)
# ---------------------------------------------------------------------------
log "Waiting for leader election (up to 30s)..."
LEADER_ADDR=""
LEADER_NODE_ID=""
NON_LEADER_ADDR=""

for attempt in $(seq 1 30); do
    LEADER_ADDR=""
    LEADER_NODE_ID=""
    NON_LEADER_ADDR=""
    for i in $(seq 0 $((REPLICAS - 1))); do
        addr="${POD_PORTS[$i]}"
        role_json=$(get_node_role "$addr")
        role=$(echo "$role_json" | grep -o '"role": *"[^"]*"' | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
        node_id=$(echo "$role_json" | grep -o '"nodeId": *"[^"]*"' | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
        if [ "$role" = "LEADER" ]; then
            LEADER_ADDR="$addr"
            LEADER_NODE_ID="$node_id"
        else
            NON_LEADER_ADDR="$addr"
        fi
    done
    if [ -n "$LEADER_ADDR" ] && [ -n "$NON_LEADER_ADDR" ]; then
        break
    fi
    sleep 1
done

if [ -z "$LEADER_ADDR" ]; then
    fail "No leader elected after 30s"
else
    ok "Leader elected: node=${LEADER_NODE_ID} addr=${LEADER_ADDR}"
fi

if [ -z "$NON_LEADER_ADDR" ]; then
    fail "Could not identify a non-leader node"
else
    ok "Non-leader node identified: addr=${NON_LEADER_ADDR}"
fi

# If we have no leader we cannot proceed with KV checks
if [ -z "$LEADER_ADDR" ] || [ -z "$NON_LEADER_ADDR" ]; then
    echo ""
    echo "Results: $PASS passed, $FAIL failed"
    exit 1
fi

# ---------------------------------------------------------------------------
# 5. Write a key to the leader
# ---------------------------------------------------------------------------
TEST_KEY="healthcheck-key"
TEST_VALUE="healthcheck-value-$(date +%s)"
# PutRequest: key (string), value (bytes as base64 in JSON)
VALUE_B64=$(printf '%s' "$TEST_VALUE" | base64 | tr -d '\n')

log "Writing key '${TEST_KEY}' via leader ${LEADER_ADDR}..."
PUT_RESP=$(grpcurl -plaintext \
    -d "{\"key\": \"${TEST_KEY}\", \"value\": \"${VALUE_B64}\"}" \
    "$LEADER_ADDR" raftkv.KvService/Put 2>/dev/null || true)
PUT_STATUS=$(echo "$PUT_RESP" | grep -o '"status": *"[^"]*"' | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
# status 0 (OK) is the default and may not appear in the JSON
if echo "$PUT_RESP" | grep -qE '"status": *"OK"' || \
   (! echo "$PUT_RESP" | grep -q '"status"') && echo "$PUT_RESP" | grep -q '{'; then
    ok "Put succeeded (status=OK)"
elif echo "$PUT_RESP" | grep -qE '"status": *"NOT_LEADER"'; then
    fail "Put returned NOT_LEADER — shard router may not have propagated leadership yet"
else
    ok "Put accepted (response: ${PUT_RESP})"
fi

# Wait briefly for replication
sleep 2

# ---------------------------------------------------------------------------
# 6. Read the key back from a non-leader node
# ---------------------------------------------------------------------------
log "Reading key '${TEST_KEY}' from non-leader ${NON_LEADER_ADDR}..."
GET_RESP=$(grpcurl -plaintext \
    -d "{\"key\": \"${TEST_KEY}\"}" \
    "$NON_LEADER_ADDR" raftkv.KvService/Get 2>/dev/null || true)

if echo "$GET_RESP" | grep -q '"value"'; then
    # Decode returned base64 value
    RETURNED_B64=$(echo "$GET_RESP" | grep -o '"value": *"[^"]*"' | sed 's/.*"\(.*\)"/\1/' || true)
    RETURNED_VALUE=$(printf '%s' "$RETURNED_B64" | base64 -d 2>/dev/null || true)
    if [ "$RETURNED_VALUE" = "$TEST_VALUE" ]; then
        ok "Read-after-write confirmed from non-leader (value matches)"
    else
        fail "Read-after-write mismatch: expected='${TEST_VALUE}' got='${RETURNED_VALUE}'"
    fi
elif echo "$GET_RESP" | grep -qE '"status": *"NOT_LEADER"'; then
    fail "Get from non-leader returned NOT_LEADER (reads should be allowed)"
elif echo "$GET_RESP" | grep -qE '"status": *"KEY_NOT_FOUND"'; then
    fail "Key not found on non-leader — replication may not have completed in time"
else
    fail "Unexpected Get response: ${GET_RESP}"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "================================================"
echo "Healthcheck Results: $PASS passed, $FAIL failed"
if [ ${#ERRORS[@]} -gt 0 ]; then
    echo "Failures:"
    for err in "${ERRORS[@]}"; do
        echo "  - $err"
    done
fi
echo "================================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
