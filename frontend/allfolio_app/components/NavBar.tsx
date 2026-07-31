'use client'

import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'

export default function NavBar() {
  const { initialized, authenticated, userName, userEmail, logout, isAdmin } = useAuth()

  return (
    <nav className="border-b border-gray-800 bg-gray-900">
      <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
        <Link href="/" className="text-lg font-bold tracking-tight">
          ALLFOLIO
        </Link>

        {authenticated && (
          <>
            <Link href="/unified" className="text-sm text-gray-400 hover:text-white transition-colors">
              통합 자산
            </Link>
            <Link href="/unified/accounts" className="text-sm text-gray-400 hover:text-white transition-colors">
              계좌 관리
            </Link>
            <Link href="/unified/reports" className="text-sm text-gray-400 hover:text-white transition-colors">
              보고서
            </Link>
            {isAdmin && (
              <Link href="/unified/admin/tax-rates" className="text-sm text-amber-400 hover:text-amber-300 transition-colors">
                세율 마스터
              </Link>
            )}
          </>
        )}

        <div className="ml-auto flex items-center gap-4">
          {!initialized && (
            <div className="h-4 w-20 animate-pulse rounded bg-gray-800" />
          )}
          {initialized && !authenticated && (
            <Link
              href="/login"
              className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500 transition-colors"
            >
              로그인
            </Link>
          )}
          {initialized && authenticated && (
            <div className="flex items-center gap-3">
              <span className="text-xs text-gray-400">{userEmail ?? userName}</span>
              <button
                onClick={logout}
                className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:border-gray-500 hover:text-white transition-colors"
              >
                로그아웃
              </button>
            </div>
          )}
        </div>
      </div>
    </nav>
  )
}
