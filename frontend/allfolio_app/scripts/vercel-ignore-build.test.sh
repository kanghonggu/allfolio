#!/usr/bin/env bash
# vercel-ignore-build.sh 회귀 테스트.
#
#   bash frontend/allfolio_app/scripts/vercel-ignore-build.test.sh
#
# 저장소 밖 임시 디렉터리에 이 저장소와 같은 모양(frontend/allfolio_app + allfolio-backend)의
# 가짜 저장소를 만들어 돌린다. 종료 코드 규약은 Vercel Ignored Build Step 그대로다 — 1 = 빌드, 0 = 건너뜀.
#
# 건너뛰지 말아야 할 빌드를 건너뛰는 것이 이 스크립트의 유일한 위험이다. 그래서 테스트는
# "건너뛴다"보다 "빌드한다" 쪽을 더 많이 본다.
set -u

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/vercel-ignore-build.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

check() { # 이름 기대코드 실제코드
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1))
    echo "  ok   — $1"
  else
    fail=$((fail + 1))
    echo "  FAIL — $1 (기대 $2, 실제 $3)"
  fi
}

# Vercel은 Root Directory에서 Ignored Build Step을 실행한다. 그 조건을 그대로 재현한다.
run_from_app_dir() { # 브랜치이름 -> 종료코드
  (
    cd "$TMP/work/frontend/allfolio_app" || exit 9
    VERCEL_GIT_COMMIT_REF="$1" bash "$SCRIPT" >/dev/null 2>&1
    echo $?
  )
}

commit_file() { # 경로 내용 메시지
  mkdir -p "$(dirname "$1")"
  echo "$2" > "$1"
  git add -A
  git commit -qm "$3"
}

echo "vercel-ignore-build.sh"

git init --bare -q "$TMP/origin.git"
git clone -q "$TMP/origin.git" "$TMP/work"
cd "$TMP/work" || exit 1
git config user.email t@example.com
git config user.name test
git symbolic-ref HEAD refs/heads/main
commit_file frontend/allfolio_app/package.json '{"name":"app"}' init
commit_file allfolio-backend/Main.kt 'fun main() {}' backend
git push -q origin main

# 프로덕션 브랜치는 무조건 빌드한다.
git checkout -q main
check "main은 항상 빌드한다" 1 "$(run_from_app_dir main)"

# 프로덕션을 브랜치 이름 하나에 걸지 않는다. VERCEL_GIT_COMMIT_REF가 비어도 main 커밋은 빌드한다.
check "브랜치 이름을 몰라도 main 위 커밋은 빌드한다" 1 "$(run_from_app_dir '')"

# 백엔드만 건드린 브랜치 — AF-114에서 빨간불이 뜬 다섯 건이 전부 이 모양이었다.
git checkout -q -b be-only main
commit_file allfolio-backend/Collector.kt 'class Collector' 'feat: 백엔드만'
check "백엔드만 바뀌면 건너뛴다" 0 "$(run_from_app_dir be-only)"

# 프런트엔드를 건드리면 반드시 빌드한다.
git checkout -q -b fe-only main
commit_file frontend/allfolio_app/app/page.tsx 'export default function P() {}' 'feat: 화면'
check "프런트엔드가 바뀌면 빌드한다" 1 "$(run_from_app_dir fe-only)"

# HEAD^..HEAD만 보는 순진한 구현이 놓치는 경우. 프런트엔드 변경이 푸시의 첫 커밋에 있고
# 그 뒤로 백엔드 커밋이 쌓이면, 마지막 커밋만 봐서는 변경을 못 본다.
git checkout -q -b fe-then-be main
commit_file frontend/allfolio_app/app/chart.tsx 'export const Chart = () => null' 'feat: 차트'
commit_file allfolio-backend/A.kt 'class A' 'feat: A'
commit_file allfolio-backend/B.kt 'class B' 'feat: B'
check "여러 커밋 중 첫 커밋만 프런트엔드여도 빌드한다" 1 "$(run_from_app_dir fe-then-be)"

# 브랜치가 갈라진 지점을 못 찾으면 건너뛰지 않는다(fail open).
git checkout -q -b orphan main
commit_file allfolio-backend/C.kt 'class C' 'feat: C'
git remote remove origin
check "분기점을 못 찾으면 빌드한다" 1 "$(run_from_app_dir orphan)"

echo
echo "통과 $pass · 실패 $fail"
[ "$fail" -eq 0 ]
