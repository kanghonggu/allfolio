'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'

export default function SignInPage() {
  const router = useRouter()

  useEffect(() => {
    router.replace('/login')
  }, [router])

  return (
    <div className="flex min-h-[70vh] items-center justify-center" role="status">
      <span className="animate-pulse font-mono text-[10px] tracking-wideLabel text-fg-muted">
        로그인 페이지로 이동 중 …
      </span>
    </div>
  )
}
