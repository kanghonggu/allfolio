'use client'

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { cx } from '@/lib/cx'

const GAP = 6
const EDGE = 8
const WIDTH = 264

/**
 * 지표 도움말 — 라벨 옆 (i) 아이콘 (AF-205).
 *
 * 여는 길이 셋이다:
 * - 마우스: 라벨이나 아이콘에 올리면 열리고 벗어나면 닫힌다.
 * - 터치: 아이콘을 탭하면 열리고, 다시 탭하거나 바깥을 탭하면 닫힌다.
 * - 키보드: 아이콘에 포커스가 가면 열리고, 포커스가 빠지거나 Esc면 닫힌다.
 *
 * **호버는 `pointerType === 'mouse'`일 때만 받는다.** 터치 탭도 mouseenter를 흉내 내므로
 * 그대로 받으면 탭 한 번에 "호버로 열림 → click으로 토글되어 닫힘"이 일어나 모바일에서 안 열린다.
 *
 * 말풍선은 `position: fixed`로 띄우고 열 때 화면 안으로 좌표를 잘라 넣는다. absolute로 두면
 * 표의 `overflow-x-auto` 안에서 잘리고, 2열 카드의 오른쪽 칸에서 모바일 화면 밖으로 나간다.
 *
 * 인쇄물엔 호버가 없다 — 아이콘과 말풍선 모두 `no-print`로 뺀다(globals.css).
 */
export default function InfoTip({
  label,
  text,
  children,
  className,
}: {
  /** 무엇에 대한 설명인지 — 스크린리더용 버튼 이름에 쓴다 */
  label: string
  text: React.ReactNode
  /** 함께 호버 영역이 될 화면 라벨. 없으면 아이콘만 그린다 */
  children?: React.ReactNode
  className?: string
}) {
  const [hover, setHover] = useState(false)
  const [pinned, setPinned] = useState(false)
  const [focused, setFocused] = useState(false)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const wrapRef = useRef<HTMLSpanElement>(null)
  const btnRef = useRef<HTMLButtonElement>(null)
  const tipRef = useRef<HTMLSpanElement>(null)
  const id = useId()

  const open = hover || pinned || focused

  const close = useCallback(() => {
    setHover(false)
    setPinned(false)
    setFocused(false)
  }, [])

  const place = useCallback(() => {
    const btn = btnRef.current
    if (!btn) return
    const r = btn.getBoundingClientRect()
    const vw = window.innerWidth
    const vh = window.innerHeight
    const width = Math.min(WIDTH, vw - EDGE * 2)
    const left = Math.min(Math.max(r.left - 4, EDGE), vw - width - EDGE)
    const height = tipRef.current?.offsetHeight ?? 0
    // 아래 공간이 모자라면 위로 띄운다
    const below = r.bottom + GAP
    const top = height && below + height > vh - EDGE ? Math.max(r.top - GAP - height, EDGE) : below
    setPos({ top, left })
  }, [])

  useLayoutEffect(() => {
    if (open) place()
  }, [open, place])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    const onDown = (e: PointerEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) close()
    }
    window.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onDown)
    window.addEventListener('scroll', place, true)
    window.addEventListener('resize', place)
    return () => {
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('pointerdown', onDown)
      window.removeEventListener('scroll', place, true)
      window.removeEventListener('resize', place)
    }
  }, [open, close, place])

  return (
    <span
      ref={wrapRef}
      className={cx('inline-flex items-center gap-1', className)}
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') setHover(true) }}
      onPointerLeave={(e) => { if (e.pointerType === 'mouse') setHover(false) }}
    >
      {children}
      <button
        ref={btnRef}
        type="button"
        aria-label={`${label} 설명`}
        aria-expanded={open}
        aria-describedby={open ? id : undefined}
        onClick={() => {
          // 키보드 포커스로 이미 열린 상태에서 Enter/Space면 닫는 쪽으로 읽는다
          if (focused) { setFocused(false); setPinned(false); return }
          setPinned((p) => !p)
        }}
        // 키보드 포커스만 받는다. 탭·클릭도 포커스를 주는 브라우저(Android Chrome 등)에서
        // 이걸 열기로 받으면 두 번째 탭이 pinned만 끄고 포커스가 남아 닫히지 않는다.
        onFocus={(e) => { if (e.currentTarget.matches(':focus-visible')) setFocused(true) }}
        onBlur={() => setFocused(false)}
        // 보이는 원은 13px이지만 before로 탭 영역을 25px까지 넓힌다 — 손가락으로 맞히기엔 13px이 작다
        className="no-print relative inline-flex h-[13px] before:absolute before:-inset-[6px] before:content-[''] w-[13px] shrink-0 cursor-help items-center justify-center rounded-full border border-line font-serif text-[9px] italic leading-none text-fg-faint transition-colors hover:border-ink hover:text-ink focus-visible:border-ink focus-visible:text-ink focus-visible:outline-none"
      >
        i
      </button>
      {open && (
        <span
          ref={tipRef}
          id={id}
          role="tooltip"
          style={{
            position: 'fixed',
            top: pos?.top ?? -9999,
            left: pos?.left ?? -9999,
            width: `min(${WIDTH}px, calc(100vw - ${EDGE * 2}px))`,
          }}
          className="no-print z-50 block border border-line-card bg-surface px-3 py-2.5 text-left font-sans text-[12px] font-normal normal-case leading-[1.6] tracking-normal text-fg-2 shadow-sm"
        >
          {text}
        </span>
      )}
    </span>
  )
}
