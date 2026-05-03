'use client'

import { useEffect } from 'react'
import { useKeycloak } from '@/contexts/KeycloakContext'

// NextAuth가 /auth/signin으로 보내던 경우 대비 — 그냥 keycloak.login()으로 넘김
export default function SignInPage() {
  const { initialized, authenticated, login } = useKeycloak()

  useEffect(() => {
    if (initialized && !authenticated) login()
  }, [initialized, authenticated, login])

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <div className="text-center space-y-3">
        <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
        <p className="text-sm text-gray-400">로그인 페이지로 이동 중...</p>
      </div>
    </div>
  )
}
