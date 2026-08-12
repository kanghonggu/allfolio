#!/usr/bin/env bash
# AF-100 ECOS 과거 환율 백필 — 연 단위로 쪼개 순차 호출한다.
#
# 왜 쪼개나: 저장이 행마다 merge SELECT를 내므로 한 번에 다년 구간을 넣으면
# 트랜잭션이 길어진다(운영은 Neon 무료 플랜). 백필은 멱등하므로 나눠 돌려도 안전하고,
# 중간에 끊겨도 성공한 구간은 그대로 남는다.
#
# 사용법:
#   export ALLFOLIO_API=https://<render-host>
#   export ALLFOLIO_ADMIN_TOKEN=<어드민 JWT>     # 이 스크립트만 읽는다
#   ./scripts/fx-backfill.sh 2020-01-01 2026-08-12 [USD]
#
# 시작일 고르기 (Neon에서):
#   SELECT MIN(flow_date) FROM cash_flow WHERE currency IN ('USD','USDT');
#   SELECT MIN(traded_at)  FROM ua_stock_trades;
set -euo pipefail

FROM="${1:?시작일이 필요합니다 (YYYY-MM-DD)}"
TO="${2:?종료일이 필요합니다 (YYYY-MM-DD)}"
CURRENCY="${3:-USD}"

: "${ALLFOLIO_API:?ALLFOLIO_API 를 설정하세요 (예: https://allfolio-api.onrender.com)}"
: "${ALLFOLIO_ADMIN_TOKEN:?ALLFOLIO_ADMIN_TOKEN 을 설정하세요}"

# macOS(BSD date)와 GNU date를 모두 지원한다
add_year() {
  date -j -v+1y -f %Y-%m-%d "$1" +%Y-%m-%d 2>/dev/null \
    || date -d "$1 +1 year" +%Y-%m-%d
}
day_before() {
  date -j -v-1d -f %Y-%m-%d "$1" +%Y-%m-%d 2>/dev/null \
    || date -d "$1 -1 day" +%Y-%m-%d
}

total_inserted=0 total_updated=0 total_unchanged=0
total_skipped=0 total_dup=0 total_oor=0
failed=0

cursor="$FROM"
while [[ "$cursor" < "$TO" || "$cursor" == "$TO" ]]; do
  next="$(add_year "$cursor")"
  chunk_end="$(day_before "$next")"
  [[ "$chunk_end" > "$TO" ]] && chunk_end="$TO"

  printf '\n[%s] %s ~ %s\n' "$CURRENCY" "$cursor" "$chunk_end"

  # 실패해도 다음 구간으로 넘어간다 — 성공한 구간은 이미 저장돼 있고 재실행이 안전하다
  # 엔드포인트가 @RequestParam 을 쓰므로 값은 쿼리스트링에 실린다.
  # 날짜·통화는 안전한 문자만 쓰므로 URL을 그대로 조립한다.
  url="$ALLFOLIO_API/api/admin/fx/backfill?currency=$CURRENCY&from=$cursor&to=$chunk_end"
  http_body="$(mktemp)"
  code="$(curl -sS -o "$http_body" -w '%{http_code}' \
    -X POST "$url" \
    -H "Authorization: Bearer $ALLFOLIO_ADMIN_TOKEN" \
    || echo 000)"

  if [[ "$code" == "200" ]]; then
    ins=$(jq -r '.inserted // 0' "$http_body"); upd=$(jq -r '.updated // 0' "$http_body")
    unc=$(jq -r '.unchanged // 0' "$http_body"); skp=$(jq -r '.skipped // 0' "$http_body")
    dup=$(jq -r '.duplicates // 0' "$http_body"); oor=$(jq -r '.outOfRange // 0' "$http_body")
    first=$(jq -r '.firstDate // "-"' "$http_body"); last=$(jq -r '.lastDate // "-"' "$http_body")
    printf '  ok  신규 %s · 갱신 %s · 무변화 %s | 스킵 %s · 중복 %s · 범위밖 %s | %s~%s\n' \
      "$ins" "$upd" "$unc" "$skp" "$dup" "$oor" "$first" "$last"
    total_inserted=$((total_inserted+ins)); total_updated=$((total_updated+upd))
    total_unchanged=$((total_unchanged+unc)); total_skipped=$((total_skipped+skp))
    total_dup=$((total_dup+dup)); total_oor=$((total_oor+oor))
  else
    failed=$((failed+1))
    # 응답 코드가 곧 다음 행동이다:
    #   400 요청이 잘못됨(통화·기간) · 409 동시 실행, 재실행하면 됨
    #   500 우리 설정 누락(ECOS_API_KEY 등) · 502 ECOS 쪽 문제
    printf '  FAIL HTTP %s  %s\n' "$code" "$(jq -c '.' "$http_body" 2>/dev/null || cat "$http_body")"
    if [[ "$code" == "500" || "$code" == "400" ]]; then
      echo "  → 설정/요청 문제라 이어서 돌려도 같은 결과입니다. 중단합니다."
      rm -f "$http_body"; break
    fi
  fi
  rm -f "$http_body"

  [[ "$chunk_end" == "$TO" ]] && break
  cursor="$next"
  sleep 2   # 무료 플랜 배려
done

printf '\n합계: 신규 %s · 갱신 %s · 무변화 %s | 스킵 %s · 중복 %s · 범위밖 %s | 실패 구간 %s\n' \
  "$total_inserted" "$total_updated" "$total_unchanged" \
  "$total_skipped" "$total_dup" "$total_oor" "$failed"

cat <<'EOF'

확인:
  SELECT currency, COUNT(*), MIN(base_date), MAX(base_date) FROM fx_rate_daily GROUP BY currency;

갱신(updated)이 0이 아니면 기존 환율이 정정된 것이고, 백엔드 캐시는 백필 성공 시 자동으로 비워진다.
EOF
