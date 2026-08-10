'use client'

import Link from 'next/link'

/**
 * AF-92: 계좌가 하나도 없는 사용자에게 첫 등록 경로를 먼저 묻는다.
 *
 * 선택지를 둘로만 압축한 이유 — 계좌 추가 화면의 수집 방식 5개는 신규 사용자에게
 * 너무 많고, "증권사 API 연동"과 "증권 계좌"의 차이가 그 자리에서 드러나지 않는다.
 * 상세 선택은 다음 화면으로 미룬다.
 *
 * 직접 입력을 위에 둔 것은 의도적이다. API 키는 증권사 사이트에서 개발자 신청을 하고
 * 승인을 기다려야 해서, 처음 써보는 사람에게 권할 첫 경로가 아니다.
 */
export default function WelcomeModal({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/25 p-4"
      onClick={onDismiss}
      role="dialog"
      aria-modal="true"
      aria-labelledby="welcome-title"
    >
      <div
        className="w-full max-w-md border border-ink bg-surface p-6"
        onClick={(e) => e.stopPropagation()}
      >
        <span className="font-mono text-[10px] tracking-brand text-fg-muted">ALLFOLIO</span>
        <h2 id="welcome-title" className="m-0 mt-2.5 font-serif text-[19px] font-semibold tracking-[-0.01em]">
          자산을 어떻게 등록할까요?
        </h2>
        <p className="mt-1.5 text-xs text-fg-faint">
          한 가지만 골라 시작하면 됩니다. 나머지는 나중에 얼마든지 추가할 수 있습니다.
        </p>

        <div className="mt-5 space-y-3">
          <Link
            href="/unified/accounts/new?mode=manual"
            onClick={onDismiss}
            className="block border border-ink bg-ink p-4 text-white transition-colors hover:bg-fg-2"
          >
            <p className="m-0 text-sm font-medium">직접 입력</p>
            <p className="m-0 mt-1 text-xs leading-snug text-white/70">
              계좌를 만들고 보유 종목·거래를 직접 적습니다 · 준비물 없음
            </p>
          </Link>

          <Link
            href="/unified/accounts/new?mode=connect"
            onClick={onDismiss}
            className="block border border-line p-4 transition-colors hover:border-ink"
          >
            <p className="m-0 text-sm font-medium">증권사 · 거래소 연동</p>
            <p className="m-0 mt-1 text-xs leading-snug text-fg-faint">
              API 키로 잔고를 자동 수집합니다 · 가장 정확하지만 키 발급이 필요합니다
            </p>
          </Link>
        </div>

        <button
          type="button"
          onClick={onDismiss}
          className="mt-5 w-full py-2 text-xs text-fg-faint transition-colors hover:text-ink"
        >
          나중에 할게요
        </button>
      </div>
    </div>
  )
}
