'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { cx } from '@/lib/cx'

const NAV_ITEMS = [
  { href: '/unified', label: '통합 자산', exact: true },
  { href: '/unified/accounts', label: '계좌' },
  { href: '/unified/reports', label: '보고서' },
  { href: '/unified/cashflow', label: '현금흐름' },
  { href: '/unified/recon', label: '대사·검증' },
]

const ADMIN_ITEMS = [
  { href: '/unified/admin/tax-rates', label: '세율 마스터' },
  { href: '/unified/admin/exclusion-presets', label: '배제 프리셋' },
  { href: '/unified/admin/ops', label: '운영 모니터링' },
]

export default function NavBar() {
  const { initialized, authenticated, userName, userEmail, logout, isAdmin } = useAuth()
  const pathname = usePathname()

  const isActive = (href: string, exact?: boolean) =>
    exact ? pathname === href : pathname === href || pathname.startsWith(`${href}/`)

  return (
    <nav className="border-b border-ink bg-surface">
      <div className="mx-auto flex h-[52px] max-w-[1400px] items-center gap-6 px-4">
        <Link href="/" className="shrink-0 font-mono text-[12.5px] font-semibold tracking-brand text-ink">
          ALLFOLIO
        </Link>

        {authenticated && (
          <div className="flex items-center gap-1 overflow-x-auto [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            {NAV_ITEMS.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className={cx(
                  'whitespace-nowrap border-b-2 px-2.5 py-1.5 text-[13px] transition-colors',
                  isActive(item.href, item.exact)
                    ? 'border-ink text-ink'
                    : 'border-transparent text-fg-3 hover:text-ink',
                )}
              >
                {item.label}
              </Link>
            ))}
            {isAdmin &&
              ADMIN_ITEMS.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cx(
                    'whitespace-nowrap border-b-2 px-2.5 py-1.5 text-[13px] transition-colors',
                    isActive(item.href)
                      ? 'border-warn text-warn'
                      : 'border-transparent text-warn opacity-70 hover:opacity-100',
                  )}
                >
                  {item.label}
                </Link>
              ))}
          </div>
        )}

        <div className="ml-auto flex shrink-0 items-center gap-3">
          {!initialized && <div className="h-4 w-20 animate-pulse bg-line-soft" />}
          {initialized && !authenticated && (
            <Link
              href="/login"
              className="border border-ink bg-ink px-4 py-1.5 text-sm text-white transition-colors hover:border-fg-2 hover:bg-fg-2"
            >
              로그인
            </Link>
          )}
          {initialized && authenticated && (
            <div className="flex items-center gap-3">
              <span className="hidden font-mono text-[10px] tracking-label text-fg-faint sm:inline">
                {userEmail ?? userName}
              </span>
              <button
                onClick={logout}
                className="border border-line px-3 py-1.5 text-xs text-fg-3 transition-colors hover:border-ink hover:text-ink"
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
