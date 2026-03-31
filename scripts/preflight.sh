#!/bin/bash
set -euo pipefail

PASS=0
FAIL=0

check() {
    local name="$1"
    local cmd="$2"
    if eval "$cmd" &>/dev/null; then
        echo "  [OK] $name"
        ((PASS++)) || true
    else
        echo "  [FAIL] $name"
        ((FAIL++)) || true
    fi
}

echo "raftkv preflight checks"
echo "========================"

check "Docker daemon running"        "docker info"
check "minikube cluster running"     "minikube status | grep -q 'host: Running'"
check "kubectl can reach cluster"    "kubectl cluster-info"
check "Java 21+"                     "java -version 2>&1 | grep -qE 'version \"2[1-9]|version \"[3-9][0-9]'"
check "Gradle 8+"                    "gradle --version 2>&1 | grep -qE 'Gradle [8-9]|Gradle [1-9][0-9]'"
check "minikube image load works"    "minikube image ls"

echo ""
echo "Results: $PASS passed, $FAIL failed"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "Preflight failed. Fix the above before starting the pipeline."
    exit 1
fi

echo "All checks passed. Ready to start."
