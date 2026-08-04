import type { Metadata } from 'next'
import { IBM_Plex_Sans_KR, IBM_Plex_Mono, IBM_Plex_Serif, Noto_Serif_KR } from 'next/font/google'
import './globals.css'
import { Providers } from './providers'
import NavBar from '@/components/NavBar'

const plexSans = IBM_Plex_Sans_KR({
  weight: ['300', '400', '500', '600', '700'],
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-sans',
})

const plexMono = IBM_Plex_Mono({
  weight: ['400', '500', '600'],
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-mono',
})

const plexSerif = IBM_Plex_Serif({
  weight: ['400', '500', '600'],
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-serif',
})

// IBM Plex Serif에는 한글 글리프가 없어 한글 세리프는 Noto Serif KR로 폴백한다
const notoSerifKr = Noto_Serif_KR({
  weight: ['400', '500', '600'],
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-serif-kr',
})

export const metadata: Metadata = {
  title: 'Allfolio',
  description: '포트폴리오 데이터 검증 대시보드',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html
      lang="ko"
      className={`${plexSans.variable} ${plexMono.variable} ${plexSerif.variable} ${notoSerifKr.variable}`}
    >
      <body className="min-h-screen bg-canvas font-sans text-ink antialiased">
        <Providers>
          <NavBar />
          <main className="mx-auto w-full max-w-[1400px] px-4 py-8">{children}</main>
        </Providers>
      </body>
    </html>
  )
}
