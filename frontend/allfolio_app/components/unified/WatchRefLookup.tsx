'use client'

import { useEffect, useRef, useState } from 'react'
import { useWatchApi } from '@/lib/useApi'
import type { WatchRefCandidate, WatchRefLookup as Result } from '@/lib/watch-api'
import Button from '@/components/ui/Button'
import { Input } from '@/components/ui/Field'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { money } from '@/lib/format'

/**
 * 시계 ref 확인 (W6).
 *
 * ## 왜 검색이 아니라 확인인가
 *
 * R2(단지·평형)는 목록에서 고르게 했다. 사용자가 단지일련번호와 전용면적을 **모르기**
 * 때문이다. 시계는 다르다 — ref는 보증서·케이스백에 적혀 있어 읽어 올 수 있다.
 *
 * 반대로 이름으로 찾게 하려면 목록이 필요한데 그 목록을 만들 수 없다. 실측에서
 * watchpricedata `/api/search`는 원본 문서를 주고 brand가 `롤렉스`/`로렉스`로, model이
 * `데이져스트`/`DJ 26mm`로 갈린다. 그걸 여기서 묶으면 **R2가 막으려던 바로 그 불일치를
 * 화면에서 다시 만든다.**
 *
 * ## 모델명으로 후보 찾기 (AF-207) — 확인 단계 **앞에** 붙는다
 *
 * 데모 리뷰(2026-09-09): "보통 데이토나라고 검색하지, 레퍼런스 번호는 UX적으로 안 좋다."
 * 상류가 ref 단위로 묶은 후보 목록(watch-data #51 `/api/refs`)을 만들어서, 위의 이유로 막혔던
 * 길이 열렸다. 묶는 일은 상류가 하고 이 화면은 **고르기만** 한다.
 *
 * - 한 입력창에 모델명이든 ref든 치면(디바운스) 서버 경유로 후보를 띄운다.
 * - 후보를 고르면 그 `refKey`를 **그대로** 아래 확인 단계에 넘긴다. 저장 키는 여전히 확인
 *   단계가 돌려준 값이다 — 검색 결과가 저장 키를 정하지 않는다.
 * - 🔴 **확인 버튼(직접 입력 경로)은 검색과 무관하게 늘 살아 있다.** 상류 질의가 무겁거나
 *   상류가 아프면 서버는 3초에서 끊고 `UNAVAILABLE`을 준다(상류는 상시 실행이라 콜드스타트는 없다). 그때 화면은 "번호로 직접
 *   확인하라"고 말한다. 후보 0건(`OK`+`[]`)과 문구가 다르다 — '없다'와 '못 찾았다'를 섞지 않는다.
 *
 * ## 🔴 저장하는 값은 서버가 **매칭에 쓴 키**다
 *
 * [onConfirm]에 넘기는 것은 응답의 `ref`다. 상류가 정규화한 결과이지 사용자가 친 문자열이
 * 아니다. 실측(2026-09-08):
 *
 * | 입력 | refKey | 표본 |
 * |---|---|---|
 * | `126300ln` | `126300LN` | — |
 * | `  116238   chsj  ` | `116238 CHSJ` | **1건** |
 * | `116238` | `116238` | 0건 |
 *
 * 대소문자·공백·괄호 주석은 접히지만 **소재 기호(`CHSJ`)는 안 잘린다.** 자르면 스틸과 금이
 * 한 중앙값에 섞이기 때문이고, 그래서 `116238`과 `116238 CHSJ`는 **다른 시계로 다뤄진다.**
 * 화면 안내가 "적은 그대로 찾는다"고 말하는 근거가 이것이다.
 *
 * 🔴 2026-09-02에 이걸 "상류는 정규화하지 않는다"고 잘못 적었다. 그때 던져 본 두 입력이
 * 하필 정규화해도 그대로인 값이었다 — **되울림과 항등을 구분하는 입력을 골라야 한다.**
 *
 * ## 표본이 없어도 등록을 막지 않는다
 *
 * 시세를 못 구하는 시계도 자산으로는 존재한다. 사용자가 취득가를 넣어 보유 현황에 두는
 * 것이 맞다. 화면은 **"자동 평가가 안 된다"고만 말한다.**
 */
export default function WatchRefLookup({
  onConfirm,
}: {
  /** 확인된 ref. **상류가 정규화해 매칭에 쓴 키다** — 소재 기호는 안 잘린다(위 KDoc 표) */
  onConfirm: (ref: string) => void
}) {
  const api = useWatchApi()
  const [ref, setRef] = useState('')
  /** 확인 단계에 실제로 넘긴 값. 후보를 고르면 입력창 글자가 아니라 refKey다 */
  const [lookedUp, setLookedUp] = useState('')
  const [result, setResult] = useState<Result | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState<SearchState>({ kind: 'idle' })
  /** 후보를 골라 입력창을 refKey로 바꾼 직후에는 다시 검색하지 않는다 */
  const skipSearchFor = useRef<string | null>(null)

  const canLookup = ref.trim().length > 0 && !loading

  // 모델명·ref 후보 찾기. 디바운스 + 이전 요청 취소로, 늦게 온 옛 응답이 새 목록을 덮지 않게 한다.
  useEffect(() => {
    const q = ref.trim()
    if (skipSearchFor.current !== null && skipSearchFor.current === ref) {
      skipSearchFor.current = null
      return
    }
    skipSearchFor.current = null
    if (!api || q.length < MIN_QUERY_LENGTH) {
      setSearch({ kind: 'idle' })
      return
    }
    const ctrl = new AbortController()
    const timer = setTimeout(async () => {
      setSearch({ kind: 'searching' })
      try {
        const res = await api.searchRefs(q, ctrl.signal)
        if (ctrl.signal.aborted) return
        setSearch(
          res.status === 'OK'
            ? { kind: 'ok', query: q, candidates: res.candidates ?? [] }
            : { kind: 'unavailable' },
        )
      } catch {
        if (ctrl.signal.aborted) return
        // 네트워크·타임아웃·우리 서버 오류. 사용자가 할 일은 서버의 UNAVAILABLE과 같다 —
        // 번호로 직접 확인하는 것. 그래서 같은 안내로 보낸다("후보 없음"으로는 보내지 않는다).
        setSearch({ kind: 'unavailable' })
      }
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
      ctrl.abort()
    }
  }, [api, ref])

  const lookup = async (target: string = ref.trim()) => {
    if (!api || loading || target.length === 0) return
    setLoading(true)
    setError(null)
    setResult(null)
    setLookedUp(target)
    try {
      setResult(await api.lookupRef(target))
    } catch {
      // 사유를 구분하지 않는다 — 사용자가 할 일은 어느 쪽이든 "다시 시도"뿐이다
      // (ComplexPicker와 같은 판단).
      setError('시세를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  const pick = (c: WatchRefCandidate) => {
    // 🔴 refKey를 **그대로** 넘긴다. 표시용 ref(원문)를 넘기면 상류 정규화에 다시 맡기는 셈이고,
    // 저장 키는 어차피 확인 단계 응답이 정한다.
    skipSearchFor.current = c.refKey
    setRef(c.refKey)
    setSearch({ kind: 'idle' })
    lookup(c.refKey)
  }

  return (
    <div className="border border-line-soft bg-surface-muted p-3.5">
      <Label size="sm" tone="faint">레퍼런스 확인</Label>

      <div className="mt-2 flex flex-wrap gap-2">
        <Input
          type="text"
          value={ref}
          placeholder="예: Daytona 또는 126300"
          aria-label="모델명 또는 레퍼런스 번호"
          autoComplete="off"
          onChange={e => {
            setRef(e.target.value)
            setResult(null)
            setError(null)
          }}
          onKeyDown={e => {
            if (e.key === 'Enter') {
              // 등록 폼 안에 있으므로 엔터가 폼을 제출하지 않게 막는다
              e.preventDefault()
              lookup()
            }
          }}
          className="min-w-[180px] flex-1"
        />
        <Button type="button" onClick={() => lookup()} disabled={!canLookup}>
          {loading ? '확인 중…' : '확인'}
        </Button>
      </div>

      {/* 대소문자·공백은 서버가 맞춰 주지만 **소재 기호는 안 잘린다** — `116238`은 0건인데
          `116238 CHSJ`는 1건이다. 사용자가 할 수 있는 일은 그 꼬리를 넣고 빼 보는 것뿐이라
          안내도 거기까지만 말한다. */}
      <p className="mt-1.5 text-[11px] text-fg-faint">
        모델명을 치면 후보가 뜹니다. 번호를 알면 바로 <strong>확인</strong>을 누르세요 —
        보증서·케이스백에 적힌 번호를 <strong>적은 그대로 찾습니다</strong>.
        안 나오면 뒤에 붙는 소재 기호(<code>116238 CHSJ</code>)를 넣거나 빼서 다시 확인해 보세요.
      </p>

      <div aria-live="polite">
        {search.kind === 'searching' && (
          <p className="mt-2 text-[11px] text-fg-faint">후보를 찾는 중…</p>
        )}

        {/* 🔴 검색 불가 — 후보 없음과 다른 문구다. 직접 입력 경로로 보낸다 */}
        {search.kind === 'unavailable' && (
          <p className="mt-2 text-[11px] text-fg-faint">
            지금 모델명 검색을 쓸 수 없습니다. <strong>레퍼런스 번호로 직접 확인하세요.</strong>
          </p>
        )}

        {/* 후보 0건. 운영 모델명은 거의 영문이라 한글은 상류 사전에 있을 때만 걸린다 */}
        {search.kind === 'ok' && search.candidates.length === 0 && (
          <p className="mt-2 text-[11px] text-fg-faint">
            <strong className="break-all">{search.query}</strong> 에 맞는 모델을 찾지 못했습니다.
            영문 모델명(예: Daytona)이나 레퍼런스 번호로도 찾아보세요.
          </p>
        )}
      </div>

      {search.kind === 'ok' && search.candidates.length > 0 && (
        <ul className="mt-2 max-h-64 overflow-y-auto border border-line-soft bg-surface">
          {search.candidates.map(c => (
            <li key={c.refKey} className="border-b border-line-soft last:border-b-0">
              <button
                type="button"
                onClick={() => pick(c)}
                className="flex w-full flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 px-3 py-2 text-left hover:bg-surface-muted"
              >
                <span className="min-w-0 flex-1">
                  <span className="block break-words text-[12.5px]">
                    {c.brand && <span className="text-fg-faint">{c.brand} · </span>}
                    {c.modelTitle ?? c.ref}
                  </span>
                  <span className="block break-all font-mono text-[11px] text-fg-faint">{c.ref}</span>
                </span>
                {c.officialPriceKrw != null && (
                  <span className="shrink-0 text-[11px] text-fg-faint">정가 {money(c.officialPriceKrw)}</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

      {error && <p role="alert" className="mt-2 text-xs text-danger">{error}</p>}

      {result && !result.found && (
        <div className="mt-3 border border-line-soft bg-surface p-3">
          <p className="text-[12.5px]">
            <strong className="break-all">{lookedUp}</strong> 의 최근 시세를 찾지 못했습니다.
          </p>
          {/* 실패가 아니라는 것을 분명히 말한다 — 표본 3건 미만은 흔한 경우다 */}
          <p className="mt-1 text-[11px] text-fg-faint">
            등록은 할 수 있습니다. 다만 <strong>자동 평가가 되지 않아</strong> 현재 가치는
            직접 입력한 값으로 남습니다.
          </p>
        </div>
      )}

      {result?.found && result.ref && (
        <div className="mt-3 border border-line bg-surface p-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="font-mono text-[13px]">{result.ref}</span>
            <Num className="text-[15px]">{money(result.medianKrw ?? null)}</Num>
          </div>

          {/* 🔴 성격을 라벨에 박는다. 체결가로 읽히면 손익이 왜곡된다(설계 7절 라벨 예시) */}
          <p className="mt-1 text-[11px] text-fg-faint">
            매물 호가 중앙값 · 표본 {result.sampleSize ?? 0}건
            {result.windowDays ? ` · 최근 ${result.windowDays}일` : ''}
            {result.asOf ? ` · ${result.asOf} 기준` : ''}
          </p>
          {result.officialPriceKrw != null && (
            <p className="mt-0.5 text-[11px] text-fg-faint">
              정가 {money(result.officialPriceKrw)}
            </p>
          )}
          {/* 체결가가 아니라는 것을 한 번 더 말한다 — 이 화면에서 가장 오해하기 쉬운 지점이다 */}
          <p className="mt-1.5 text-[11px] text-fg-ghost">
            해외 매물과 개인 판매 희망가가 섞인 값입니다. 실제 매도가와 다를 수 있습니다.
          </p>

          <Button
            type="button"
            className="mt-2.5"
            onClick={() => onConfirm(result.ref!)}
          >
            이 레퍼런스로 등록
          </Button>
        </div>
      )}
    </div>
  )
}

/** 상류 `/api/refs`도 2자 미만은 빈 목록이다 — 그 전에는 부르지 않는다 */
const MIN_QUERY_LENGTH = 2
const SEARCH_DEBOUNCE_MS = 300

type SearchState =
  | { kind: 'idle' }
  | { kind: 'searching' }
  /** 상류가 답했다. 0건일 수 있다 — "후보 없음" */
  | { kind: 'ok'; query: string; candidates: WatchRefCandidate[] }
  /** 상류 타임아웃·장애·배포 전, 또는 우리 서버 오류 — "검색 불가" */
  | { kind: 'unavailable' }
