'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'

/**
 * ADMIN 전용 화면에서 최상단 호출. 비-admin/미인증이면 redirectTo로 이동.
 * ready === true 일 때만 admin 콘텐츠를 렌더링하면 된다.
 */
export function useRequireAdmin(redirectTo = '/') {
  const { initialized, authenticated, isAdmin } = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (!initialized) return
    if (!authenticated || !isAdmin) router.replace(redirectTo)
  }, [initialized, authenticated, isAdmin, router, redirectTo])

  return { ready: initialized && authenticated && isAdmin }
}
