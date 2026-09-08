"""README의 "프론트엔드 페이지" 목록이 실제 라우트와 같은지 검사한다.

## 왜 필요한가 (AF-187)

2026-09-08 실측: README에 **15개**가 적혀 있었는데 실제 `app/**/page.tsx`는 **53개**였다.
README 마지막 수정이 2026-07-13이었으니 두 달 가까이 어긋난 채였고, **문서만 보면 화면의
절반 이상이 없는 것처럼 보였다.**

목록을 한 번 맞추는 것만으로는 또 어긋난다. 페이지를 추가하는 사람이 README를 기억해야
하기 때문이다. 그래서 **기억 대신 검사**를 둔다 — 이 저장소가 어댑터·컨트롤러에 배선
테스트를 두는 것과 같은 판단이다.

## 무엇을 검사하나

`app` 아래 `page.tsx`의 경로 집합과, README의 "프론트엔드 페이지" 절에 적힌 경로 집합이
**정확히 같은지**만 본다. 설명 문구는 안 본다 — 그건 사람이 쓰는 것이고 기계가 판단할
수 없다.

동적 구간은 `[id]` → `{id}`로 바꿔 비교한다. README에 대괄호를 쓰면 마크다운 링크로
읽히기 때문이다.

사용:
  python3 .github/scripts/check_readme_routes.py
"""
import pathlib
import re
import sys

APP = pathlib.Path("frontend/allfolio_app/app")
README = pathlib.Path("README.md")
HEADING = "## 프론트엔드 페이지"


def actual_routes() -> set[str]:
    out = set()
    for f in APP.rglob("page.tsx"):
        r = "/" + str(f.relative_to(APP).parent).replace("\\", "/")
        r = "/" if r in ("/.", "/") else r.rstrip("/")
        out.add(re.sub(r"\[([A-Za-z]+)\]", r"{\1}", r))
    return out


def documented_routes() -> set[str]:
    text = README.read_text(encoding="utf-8")
    if HEADING not in text:
        raise SystemExit(f"README에 '{HEADING}' 절이 없다")
    sec = text[text.index(HEADING):]
    end = sec.find("\n## ", len(HEADING))
    if end != -1:
        sec = sec[:end]
    # 줄 맨 앞의 경로 + 한 줄에 둘을 적은 경우(문서형 보고서: 목록과 상세를 나란히 둔다)
    return set(re.findall(r"^\s*(/[\w{}/-]*)", sec, re.M)) | \
        set(re.findall(r"\s(/[\w{}/-]+)\s+#", sec))


def main() -> int:
    actual, doc = actual_routes(), documented_routes()
    missing, extra = sorted(actual - doc), sorted(doc - actual)

    print(f"실제 {len(actual)}개 · README {len(doc)}개")
    for r in missing:
        print(f"  🔴 README에 없다: {r}")
    for r in extra:
        print(f"  🔴 README에만 있다: {r}")

    if missing or extra:
        print(f"\n{HEADING} 절을 고칠 것. 화면을 더했으면 목록에도 한 줄 더한다.")
        return 1
    print("✅ 일치")
    return 0


if __name__ == "__main__":
    sys.exit(main())
