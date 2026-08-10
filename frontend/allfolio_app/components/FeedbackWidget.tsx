'use client'

import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useAuth } from '@/contexts/AuthContext'
import { createFeedbackApi, type FeedbackKind } from '@/lib/feedback-api'
import { collectDiagnostics, installDiagnostics } from '@/lib/diagnostics'
import Button from '@/components/ui/Button'
import { errorMessage, toast } from '@/lib/toast'

const KINDS: { value: FeedbackKind; label: string }[] = [
  { value: 'BUG',         label: '버그' },
  { value: 'IMPROVEMENT', label: '개선' },
  { value: 'QUESTION',    label: '문의' },
]

const MAX_LENGTH = 2000

/**
 * 앱 내 1:1 문의 (AF-94). 사용자가 적는 건 유형과 본문 한 칸이 전부다 —
 * 제목까지 받으면 항목이 늘어날수록 안 쓴다. 재현에 필요한 화면·에러 정보는
 * 화면이 알아서 함께 보낸다.
 */
export default function FeedbackWidget() {
  const { authenticated, accessToken } = useAuth()
  const [open, setOpen] = useState(false)
  const [kind, setKind] = useState<FeedbackKind>('BUG')
  const [message, setMessage] = useState('')

  useEffect(() => { installDiagnostics() }, [])

  const submit = useMutation({
    mutationFn: () => {
      const diagnostics = collectDiagnostics()
      return createFeedbackApi(accessToken!).submit({ kind, message, ...diagnostics })
    },
    onSuccess: () => {
      toast.info('의견이 접수되었습니다. 읽고 반영하겠습니다')
      setMessage('')
      setOpen(false)
    },
    onError: (e) => toast.error(errorMessage(e, '접수에 실패했습니다. 잠시 후 다시 시도해주세요')),
  })

  // 로그인 사용자만 — 비로그인 문의는 스팸 방어 부담이 커서 범위에서 제외했다
  if (!authenticated) return null

  return (
    <>
      {!open && (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="fixed bottom-5 right-5 z-40 border border-ink bg-surface px-4 py-2.5 text-[12.5px] text-ink shadow-sm transition-colors hover:bg-ink hover:text-white"
        >
          의견 보내기
        </button>
      )}

      {open && (
        <div
          className="fixed inset-0 z-50 flex items-end justify-end bg-ink/25 p-4 sm:items-center sm:justify-center"
          onClick={() => setOpen(false)}
          role="dialog"
          aria-modal="true"
          aria-labelledby="feedback-title"
        >
          <div
            className="w-full max-w-sm border border-ink bg-surface p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 id="feedback-title" className="m-0 text-[15px] font-semibold">의견 보내기</h3>
            <p className="mt-1 text-xs text-fg-faint">
              어느 화면에서 무슨 일이 있었는지는 자동으로 함께 전달됩니다
            </p>

            <div className="mt-4 flex border border-line">
              {KINDS.map((k) => (
                <button
                  key={k.value}
                  type="button"
                  onClick={() => setKind(k.value)}
                  aria-pressed={kind === k.value}
                  className={`flex-1 py-1.5 font-mono text-xs transition-colors ${
                    kind === k.value ? 'bg-ink text-white' : 'text-fg-3 hover:text-ink'
                  }`}
                >
                  {k.label}
                </button>
              ))}
            </div>

            <label htmlFor="feedback-message" className="mt-4 mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
              내용
            </label>
            <textarea
              id="feedback-message"
              value={message}
              onChange={(e) => setMessage(e.target.value.slice(0, MAX_LENGTH))}
              rows={5}
              placeholder="여기가 이상해요 — 짧게 적어도 괜찮습니다"
              className="w-full resize-y border border-line bg-surface px-3 py-2 text-[13px] text-ink outline-none transition-colors focus:border-ink"
            />
            <div className="mt-1 text-right font-mono text-[10px] text-fg-ghost">
              {message.length} / {MAX_LENGTH}
            </div>

            <div className="mt-4 flex items-center justify-end gap-2">
              <Button variant="outline" size="sm" onClick={() => setOpen(false)}>
                닫기
              </Button>
              <Button
                variant="primary"
                size="sm"
                disabled={!message.trim() || submit.isPending}
                onClick={() => submit.mutate()}
              >
                {submit.isPending ? '보내는 중 …' : '보내기'}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
