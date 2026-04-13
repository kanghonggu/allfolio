'use client'

import { signIn } from 'next-auth/react'
import { useSearchParams } from 'next/navigation'
import { useEffect, Suspense } from 'react'

// next-auth가 /auth/signin으로 리다이렉트 했을 때
// 자동으로 Keycloak으로 넘겨줌 (중간 화면 없이)
function AutoSignIn() {
  const params = useSearchParams()
  const callbackUrl = params.get('callbackUrl') ?? '/unified'

  useEffect(() => {
    signIn('keycloak', { callbackUrl })
  }, [callbackUrl])

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <div className="text-center space-y-3">
        <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
        <p className="text-sm text-gray-400">Keycloak 로그인 페이지로 이동 중...</p>
      </div>
    </div>
  )
}

export default function SignInPage() {
  return (
    <Suspense>
      <AutoSignIn />
    </Suspense>
  )
}
