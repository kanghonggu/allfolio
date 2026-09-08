'use client'

import { useState } from 'react'
import { useWatchApi } from '@/lib/useApi'
import type { WatchRefLookup as Result } from '@/lib/watch-api'
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
  const [result, setResult] = useState<Result | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const canLookup = ref.trim().length > 0 && !loading

  const lookup = async () => {
    if (!api || !canLookup) return
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      setResult(await api.lookupRef(ref.trim()))
    } catch {
      // 사유를 구분하지 않는다 — 사용자가 할 일은 어느 쪽이든 "다시 시도"뿐이다
      // (ComplexPicker와 같은 판단).
      setError('시세를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="border border-line-soft bg-surface-muted p-3.5">
      <Label size="sm" tone="faint">레퍼런스 확인</Label>

      <div className="mt-2 flex flex-wrap gap-2">
        <Input
          type="text"
          value={ref}
          placeholder="예: 126300"
          onChange={e => setRef(e.target.value)}
          onKeyDown={e => {
            if (e.key === 'Enter') {
              // 등록 폼 안에 있으므로 엔터가 폼을 제출하지 않게 막는다
              e.preventDefault()
              lookup()
            }
          }}
          className="min-w-[180px] flex-1"
        />
        <Button type="button" onClick={lookup} disabled={!canLookup}>
          {loading ? '확인 중…' : '확인'}
        </Button>
      </div>

      {/* 대소문자·공백은 서버가 맞춰 주지만 **소재 기호는 안 잘린다** — `116238`은 0건인데
          `116238 CHSJ`는 1건이다. 사용자가 할 수 있는 일은 그 꼬리를 넣고 빼 보는 것뿐이라
          안내도 거기까지만 말한다. */}
      <p className="mt-1.5 text-[11px] text-fg-faint">
        보증서·케이스백에 적힌 번호입니다. <strong>적은 그대로 찾습니다</strong> —
        안 나오면 뒤에 붙는 소재 기호(<code>116238 CHSJ</code>)를 넣거나 빼서 다시 확인해 보세요.
      </p>

      {error && <p role="alert" className="mt-2 text-xs text-danger">{error}</p>}

      {result && !result.found && (
        <div className="mt-3 border border-line-soft bg-surface p-3">
          <p className="text-[12.5px]">
            <strong>{ref.trim()}</strong> 의 최근 시세를 찾지 못했습니다.
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
