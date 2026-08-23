'use client'

import { useState } from 'react'
import { useRealEstateApi } from '@/lib/useApi'
import type { Complex, ComplexArea } from '@/lib/real-estate-api'
import Button from '@/components/ui/Button'
import { Input } from '@/components/ui/Field'

/**
 * 단지·평형 선택 (R2).
 *
 * ## 왜 손으로 못 적게 하는가
 *
 * 전용면적을 손으로 적으면 `84.97`과 `84.93`이 갈리지 않는다. 같은 단지 안에서 평형이
 * 1㎡ 미만으로 붙어 있는 쌍이 실측 146건이다(`84.6`↔`84.75` · `84.83`↔`84.86`).
 * 허용오차를 두면 다른 평형이 섞이고, 안 두면 사용자가 정확한 값을 맞힐 방법이 없다.
 *
 * **목록에서 고르면 API가 준 값이 그대로 저장되어 정확 일치가 성립한다.**
 *
 * ## 못 찾는 것도 정상이다
 *
 * 국토부 API에 "단지 목록"이라는 것이 없어서, 우리가 받아 둔 실거래에서 역으로 뽑는다.
 * **거래가 없었던 단지는 안 나오고, 그게 맞다** — 실거래가 없으면 자동 평가도 못 한다.
 * 그래서 빈 결과일 때 "없는 단지"가 아니라 "자동 평가를 할 수 없다"고 말한다.
 */
export default function ComplexPicker({
  onSelect,
}: {
  /** 단지·평형이 정해졌을 때. **면적은 API 값 그대로 넘긴다** */
  onSelect: (v: { aptSeq: string; aptName: string; exclusiveAreaM2: number; approxPyeong: number }) => void
}) {
  const api = useRealEstateApi()
  const [sgg, setSgg] = useState('')
  const [q, setQ] = useState('')
  const [results, setResults] = useState<Complex[] | null>(null)
  const [picked, setPicked] = useState<Complex | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const canSearch = /^\d{5}$/.test(sgg) && !loading

  const search = async () => {
    if (!api || !canSearch) return
    setLoading(true)
    setError(null)
    setPicked(null)
    try {
      setResults(await api.searchComplexes(sgg, q))
    } catch {
      // 사유를 구분하지 않는다 — 사용자가 할 일은 어느 쪽이든 "다시 시도"뿐이다
      setError('검색에 실패했습니다. 잠시 후 다시 시도해 주세요.')
      setResults(null)
    } finally {
      setLoading(false)
    }
  }

  const pickArea = (c: Complex, a: ComplexArea) => {
    onSelect({
      aptSeq: c.aptSeq,
      aptName: c.aptName,
      // **그대로 넘긴다.** 반올림하면 매칭이 깨진다
      exclusiveAreaM2: a.exclusiveAreaM2,
      approxPyeong: a.approxPyeong,
    })
  }

  return (
    <div className="rounded border border-border-subtle p-3">
      <p className="mb-2 font-mono text-[10px] tracking-label text-fg-muted">단지 찾기</p>

      <div className="flex gap-2">
        <Input
          type="text"
          placeholder="법정동 코드 5자리 (예: 11680)"
          value={sgg}
          onChange={e => setSgg(e.target.value.replace(/\D/g, '').slice(0, 5))}
          className="w-48"
        />
        <Input
          type="text"
          placeholder="단지명 일부 (예: 래미안)"
          value={q}
          onChange={e => setQ(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); search() } }}
        />
        <Button type="button" onClick={search} disabled={!canSearch}>
          {loading ? '검색 중' : '검색'}
        </Button>
      </div>
      <p className="mt-1 text-[10px] text-fg-muted">
        법정동 코드는 시·군·구 단위 5자리입니다. 전국을 한 번에 찾으면 같은 이름의 단지가 너무 많습니다.
      </p>

      {error && <p className="mt-2 text-[11px] text-danger">{error}</p>}

      {results?.length === 0 && (
        // **"없는 단지"라고 말하지 않는다.** 실거래가 없으면 자동 평가를 못 할 뿐이다
        <p className="mt-3 text-[11px] text-fg-muted">
          최근 실거래가 없는 단지입니다. 자동 평가가 되지 않으므로 현재 시세를 직접 입력해 주세요.
        </p>
      )}

      {results && results.length > 0 && !picked && (
        <ul className="mt-3 max-h-56 overflow-y-auto">
          {results.map(c => (
            <li key={c.aptSeq}>
              <button
                type="button"
                onClick={() => setPicked(c)}
                className="w-full border-b border-border-subtle px-1 py-2 text-left text-[12px] hover:bg-bg-subtle"
              >
                <span className="font-medium">{c.aptName}</span>
                <span className="ml-2 text-fg-muted">
                  {c.umdName}
                  {c.buildYear ? ` · ${c.buildYear}년` : ''}
                  {` · 평형 ${c.areas.length}종`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {picked && (
        <div className="mt-3">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-[12px] font-medium">{picked.aptName}</span>
            <button
              type="button"
              onClick={() => setPicked(null)}
              className="text-[10px] text-fg-muted underline"
            >
              다른 단지
            </button>
          </div>
          <p className="mb-1.5 font-mono text-[10px] tracking-label text-fg-muted">평형 선택</p>
          <ul className="max-h-48 overflow-y-auto">
            {picked.areas.map(a => (
              <li key={a.exclusiveAreaM2}>
                <button
                  type="button"
                  onClick={() => pickArea(picked, a)}
                  className="w-full border-b border-border-subtle px-1 py-2 text-left text-[12px] hover:bg-bg-subtle"
                >
                  <span className="font-medium">{a.exclusiveAreaM2}㎡</span>
                  <span className="ml-2 text-fg-muted">약 {a.approxPyeong}평</span>
                  {/* 표본이 얇으면 미리 말한다 — 고르고 나서 평가가 안 나오면 버그로 읽힌다 */}
                  <span className={`ml-2 ${a.dealCount < 3 ? 'text-warning' : 'text-fg-muted'}`}>
                    최근 거래 {a.dealCount}건{a.dealCount < 3 ? ' (표본 부족)' : ''}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
