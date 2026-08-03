'use client'

import { useEffect, useState } from 'react'
import { dismiss, subscribe, type ToastItem } from '@/lib/toast'

// 전역 토스트 스택 (QA P1 #12) — lib/toast.ts 구독, 우하단 고정
export default function Toaster() {
  const [items, setItems] = useState<ToastItem[]>([])

  useEffect(() => subscribe(setItems), [])

  if (items.length === 0) return null

  return (
    <div className="fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
      {items.map(t => (
        <div
          key={t.id}
          role="alert"
          className={`flex items-start justify-between gap-3 rounded-xl border p-3 text-sm shadow-lg ${
            t.kind === 'error'
              ? 'border-red-800 bg-red-950 text-red-300'
              : 'border-gray-700 bg-gray-900 text-gray-200'
          }`}
        >
          <span className="whitespace-pre-line">{t.message}</span>
          <button
            onClick={() => dismiss(t.id)}
            aria-label="닫기"
            className="shrink-0 text-xs opacity-60 hover:opacity-100"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  )
}
