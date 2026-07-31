'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import { ADMIN_TOOLS } from './admin-tools'

/**
 * 관리자 영역 공통 레이아웃 (AF-11).
 * USER/미인증은 useRequireAdmin이 중앙에서 차단(→ / 리다이렉트) — 하위 페이지의
 * 개별 useRequireAdmin은 심층 방어로 유지. 서버 측은 /api/admin/** hasRole(ADMIN)이 최종 방어선.
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { ready } = useRequireAdmin()
  const pathname = usePathname()

  if (!ready) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-amber-400" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border border-amber-900/60 bg-amber-950/20 px-4 py-2.5">
        <Link href="/unified/admin" className="text-sm font-semibold text-amber-400 hover:text-amber-300 transition-colors">
          ⚙ 관리자 콘솔
        </Link>
        <nav className="flex flex-wrap gap-x-4 gap-y-1">
          {ADMIN_TOOLS.map(tool => (
            <Link
              key={tool.href}
              href={tool.href}
              className={`text-sm transition-colors ${
                pathname?.startsWith(tool.href)
                  ? 'text-amber-300 font-medium'
                  : 'text-gray-400 hover:text-amber-300'
              }`}
            >
              {tool.title}
            </Link>
          ))}
        </nav>
      </div>
      {children}
    </div>
  )
}
