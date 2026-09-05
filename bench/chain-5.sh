#!/usr/bin/env bash
# k6 플래그 수정 후 성능 배치 재실행 → 기타 → 후속 재측정(chain-4)
set -uo pipefail
cd "$(dirname "$0")/.."
echo "[$(date +%T)] === chain-5: perf ==="
bench/all-perf.sh
echo "[$(date +%T)] === chain-5: misc ==="
bench/all-misc.sh
echo "[$(date +%T)] === chain-5: chain-4 reruns/variants ==="
bench/chain-4.sh
echo "[$(date +%T)] === chain-5 done ==="
