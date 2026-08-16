#!/usr/bin/env bash
# Vercel Ignored Build Step. 종료 코드가 곧 지시다 — 1이면 빌드하고, 0이면 건너뛴다.
#
# 왜 있나 (AF-114):
#   프리뷰 빌드가 리베이스한 커밋마다 실패하는 것처럼 보였지만, 실제로는 강제 푸시 10건 중 8건이
#   성공했고 실패한 5건 중 3건은 강제 푸시가 아니었다. 실패한 다섯 건의 frontend 트리 해시는
#   같은 시각에 성공한 빌드와 바이트 단위로 같았다(5ca0838·c61b966). 같은 입력이 들어가서 다른
#   결과가 나온 것이니 프런트엔드 코드의 문제가 아니다. 그리고 다섯 건 전부 프런트엔드를 한 줄도
#   건드리지 않은 커밋이었다 — 애초에 돌 필요가 없던 빌드다.
#
#   그래서 원인을 덮는 대신 노출을 줄인다. 프런트엔드가 안 바뀐 커밋에서는 빌드를 돌리지 않는다.
#   프런트엔드가 바뀐 커밋은 그대로 전부 빌드한다. 이 저장소에서 프런트엔드를 컴파일하는 곳은
#   Vercel 프리뷰 빌드 하나뿐이라(.github/workflows에 프런트엔드 빌드가 없다) 그 하나는 살려둬야 한다.
#
# 원칙: 판단이 서지 않으면 빌드한다. 건너뛴 빌드는 되돌릴 수 없고, 헛도는 빌드는 그냥 몇 분이다.
set -u

build() { echo "build — $1"; exit 1; }
skip() { echo "skip — $1"; exit 0; }

# 프로덕션 브랜치는 조건 없이 빌드한다.
if [ "${VERCEL_GIT_COMMIT_REF:-}" = "main" ]; then
  build "main은 프로덕션 브랜치다"
fi

# 무엇과 비교할지 정한다.
# HEAD^..HEAD로 마지막 커밋만 보면, 프런트엔드 변경이 푸시의 첫 커밋에 있고 뒤에 백엔드 커밋이
# 쌓인 경우를 놓친다. 그래서 마지막 커밋은 절대 기준으로 삼지 않는다.
base=""

# 1순위: Vercel이 Ignored Build Step에만 넘겨주는 값. 마지막으로 성공한 배포의 커밋이다.
# "마지막으로 배포된 것과 지금이 다른가"가 정확히 우리가 묻고 싶은 질문이다.
# (첫 구현은 origin/main 분기점만 봤는데, Vercel의 얕은 클론에는 origin/main이 없어서
#  매번 fail open으로 떨어졌다. 빌드가 하나도 안 걸러지던 원인이다.)
prev="${VERCEL_GIT_PREVIOUS_SHA:-}"
if [ -n "$prev" ]; then
  git cat-file -e "${prev}^{commit}" 2>/dev/null \
    || git fetch --quiet --depth=1 origin "$prev" >/dev/null 2>&1 \
    || true
  if git cat-file -e "${prev}^{commit}" 2>/dev/null; then
    base="$prev"
  fi
fi

# 2순위: 로컬이나 다른 CI에서 돌릴 때. main에서 갈라진 지점부터 통째로 본다.
if [ -z "$base" ] && git rev-parse --verify -q origin/main >/dev/null 2>&1; then
  base="$(git merge-base origin/main HEAD 2>/dev/null || true)"
fi
if [ -z "$base" ]; then
  if git fetch --quiet --depth=100 origin main >/dev/null 2>&1; then
    base="$(git merge-base FETCH_HEAD HEAD 2>/dev/null || true)"
  fi
fi
[ -n "$base" ] || build "비교 기준을 못 찾았다"

# HEAD가 이미 main 위에 있으면 분기점이 곧 HEAD고, 아래 diff는 무조건 비어 있다.
# VERCEL_GIT_COMMIT_REF가 비었거나 다른 값일 때 프로덕션 배포가 통째로 건너뛰어지는 걸 막는다.
if [ "$base" = "$(git rev-parse HEAD)" ]; then
  build "HEAD가 main 위에 있다"
fi

# 현재 디렉터리가 곧 Vercel Root Directory다. 여기가 안 바뀌었으면 빌드할 이유가 없다.
# git diff는 차이가 없으면 0, 있으면 1, 오류면 128을 준다. 오류는 아래 build로 떨어진다.
if git diff --quiet "$base" HEAD -- . 2>/dev/null; then
  skip "$base 이후 $(pwd)에 변경이 없다"
fi

build "$base 이후 $(pwd)에 변경이 있다"
