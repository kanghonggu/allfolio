import type { Metadata } from 'next'
import './globals.css'
import { Providers } from './providers'
import NavBar from '@/components/NavBar'

export const metadata: Metadata = {
  title: 'Allfolio',
  description: '포트폴리오 데이터 검증 대시보드',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" className="dark">
      <body className="min-h-screen bg-gray-950 text-white antialiased">
        <Providers>
          <NavBar />
          <main className="mx-auto max-w-6xl px-4 py-8">{children}</main>
        </Providers>
      </body>
    </html>
  )
}
