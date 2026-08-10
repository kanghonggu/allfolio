'use client'

import Link from 'next/link'

export interface ChecklistState {
  accountRegistered: boolean
  assetEntered: boolean
  synced: boolean
}

const STEPS: { key: keyof ChecklistState; label: string; href: string; hint: string }[] = [
  { key: 'accountRegistered', label: '계좌 등록', href: '/unified/accounts/new', hint: '자산을 담을 계좌를 먼저 만듭니다' },
  { key: 'assetEntered',      label: '자산 입력', href: '/unified/accounts',     hint: '보유 종목이나 거래내역을 넣습니다' },
  { key: 'synced',            label: '첫 동기화', href: '/unified/accounts/sync', hint: '입력한 내용이 포지션으로 반영됩니다' },
]

/**
 * AF-92: 온보딩 모달을 닫아도 다음 할 일이 화면에 남아 있게 한다.
 * 세 단계가 모두 끝나면 사라진다 — 완료 후에도 남으면 잔소리가 된다.
 */
export default function StartChecklist({ state }: { state: ChecklistState }) {
  const done = STEPS.filter((s) => state[s.key]).length
  if (done === STEPS.length) return null

  // 아직 안 끝난 첫 단계가 지금 할 일
  const current = STEPS.find((s) => !state[s.key])!

  return (
    <section className="border-b border-line bg-surface-muted px-5 py-4 sm:px-7">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <span className="font-mono text-[10px] tracking-wideLabel text-fg-muted">시작하기</span>
        <span className="flex items-center gap-1" aria-label={`${STEPS.length}단계 중 ${done}단계 완료`}>
          {STEPS.map((s) => (
            <span
              key={s.key}
              className={`block h-[6px] w-[6px] rounded-full ${state[s.key] ? 'bg-ink' : 'bg-line'}`}
            />
          ))}
        </span>
        <Link
          href={current.href}
          className="ml-auto border border-ink bg-ink px-3.5 py-1.5 text-xs text-white transition-colors hover:bg-fg-2"
        >
          {current.label} 하러 가기
        </Link>
      </div>

      <ol className="m-0 mt-3 list-none space-y-1.5 p-0">
        {STEPS.map((s) => {
          const isDone = state[s.key]
          return (
            <li key={s.key} className="flex items-baseline gap-2 text-[13px]">
              <span aria-hidden="true" className={isDone ? 'text-ok' : 'text-fg-ghost'}>
                {isDone ? '☑' : '☐'}
              </span>
              <span className={isDone ? 'text-fg-faint line-through' : 'text-ink'}>{s.label}</span>
              {!isDone && s.key === current.key && (
                <span className="text-xs text-fg-faint">— {s.hint}</span>
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
