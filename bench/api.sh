#!/usr/bin/env bash
# 사용: bench/api.sh GET|POST <path> [json-body]   — 관리자 토큰을 붙여 앱 API 를 호출한다.
BASE=${LAB_API:-http://localhost:8085}
TOKEN=${LAB_ADMIN_TOKEN:-lab-admin}
if [ "$1" = "GET" ]; then curl -s "$BASE$2"; else curl -s -X "$1" -H 'Content-Type: application/json' -H "X-Lab-Admin-Token: $TOKEN" "$BASE$2" ${3:+-d "$3"}; fi
