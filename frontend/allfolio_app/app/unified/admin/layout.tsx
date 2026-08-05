'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useRequireAdmin } from '@/lib/useRequireAdmin'
import Label from '@/components/ui/Label'
import { LoadingState } from '@/components/ui/states'
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
    return <LoadingState label="권한 확인 중" />
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-x-4 border border-warn-line bg-warn-bg px-4">
        <Link href="/unified/admin" className="py-2.5">
          <Label size="sm" className="text-warn">관리자 콘솔</Label>
        </Link>
        <nav className="flex flex-wrap gap-x-1">
          {ADMIN_TOOLS.map(tool => (
            <Link
              key={tool.href}
              href={tool.href}
              className={`whitespace-nowrap border-b-2 px-2.5 py-2.5 text-[13px] transition-colors ${
                pathname?.startsWith(tool.href)
                  ? 'border-warn text-warn'
                  : 'border-transparent text-warn opacity-70 hover:opacity-100'
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
