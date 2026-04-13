'use client'

import Link from 'next/link'
import { useSession, signIn, signOut } from 'next-auth/react'

export default function NavBar() {
  const { data: session, status } = useSession()

  return (
    <nav className="border-b border-gray-800 bg-gray-900">
      <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
        <Link href="/" className="text-lg font-bold tracking-tight">
          ALLFOLIO
        </Link>

        {session && (
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
          </>
        )}

        {/* 우측: 사용자 정보 + 로그인/아웃 */}
        <div className="ml-auto flex items-center gap-4">
          {status === 'loading' && (
            <div className="h-4 w-20 animate-pulse rounded bg-gray-800" />
          )}
          {status === 'unauthenticated' && (
            <button
              onClick={() => signIn('keycloak')}
              className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500 transition-colors"
            >
              로그인
            </button>
          )}
          {status === 'authenticated' && session && (
            <div className="flex items-center gap-3">
              <span className="text-xs text-gray-400">
                {session.user?.email ?? session.user?.name}
              </span>
              <button
                onClick={() => signOut({ callbackUrl: '/' })}
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
