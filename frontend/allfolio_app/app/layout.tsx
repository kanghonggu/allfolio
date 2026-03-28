import type { Metadata } from 'next'
import './globals.css'
import { Providers } from './providers'
import Link from 'next/link'

export const metadata: Metadata = {
  title: 'Allfolio',
  description: '포트폴리오 데이터 검증 대시보드',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className="dark">
      <body className="min-h-screen bg-gray-950 text-white antialiased">
        <Providers>
          <nav className="border-b border-gray-800 bg-gray-900">
            <div className="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
              <Link href="/" className="text-lg font-bold tracking-tight">
                ALLFOLIO
              </Link>
              <Link
                href="/"
                className="text-sm text-gray-400 hover:text-white transition-colors"
              >
                대시보드
              </Link>
              <Link
                href={`/portfolio/${process.env.NEXT_PUBLIC_DEFAULT_PORTFOLIO_ID}`}
                className="text-sm text-gray-400 hover:text-white transition-colors"
              >
                포지션
              </Link>
              <Link
                href="/trades"
                className="text-sm text-gray-400 hover:text-white transition-colors"
              >
                거래 내역
              </Link>
            </div>
          </nav>
          <main className="mx-auto max-w-6xl px-4 py-8">{children}</main>
        </Providers>
      </body>
    </html>
  )
}
