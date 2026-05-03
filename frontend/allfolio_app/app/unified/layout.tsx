'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'

export default function UnifiedLayout({ children }: { children: React.ReactNode }) {
  const { initialized, authenticated } = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (initialized && !authenticated) router.replace('/login')
  }, [initialized, authenticated, router])

  if (!initialized) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
      </div>
    )
  }

  if (!authenticated) return null

  return <>{children}</>
}
