'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'

const FEATURES = [
  { icon: '🏦', title: '멀티 계좌 통합',   desc: '거래소 API, 지갑, 증권사, 수동 입력 — 모든 자산을 하나의 화면에서' },
  { icon: '📊', title: '6종 투자 보고서', desc: '포트폴리오 요약, 수익률 분석, 자산 배분, 리스크, 포지션, 벤치마크 비교' },
  { icon: '📈', title: '실시간 NAV 추적', desc: 'Sync할 때마다 NAV 스냅샷을 기록하여 수익률 히스토리를 자동으로 누적' },
  { icon: '⚠️', title: '리스크 지표',     desc: 'Volatility, VaR 95%, MDD, Sharpe Ratio — 포트폴리오의 위험을 수치로 파악' },
  { icon: '🏁', title: '벤치마크 비교',   desc: 'S&P 500, BTC, KOSPI 대비 초과 수익(알파)을 한눈에 확인' },
  { icon: '🔒', title: '안전한 보안',     desc: '업계 표준 OAuth 2.0 방식으로 API 키와 자산 정보를 안전하게 보호' },
]

export default function LandingPage() {
  const { authenticated, initialized } = useAuth()
  const router = useRouter()

  useEffect(() => {
    if (initialized && authenticated) {
      router.replace('/unified')
    }
  }, [initialized, authenticated, router])

  if (!initialized || authenticated) {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-600 border-t-blue-400" />
      </div>
    )
  }

  return (
    <div className="space-y-24 py-8">
      {/* Hero */}
      <section className="text-center space-y-6 py-8">
        <div className="inline-flex items-center gap-2 rounded-full border border-blue-800 bg-blue-950/50 px-4 py-1.5 text-xs text-blue-400">
          회원가입 무료 · 안전한 보안 인증
        </div>
        <h1 className="text-4xl font-extrabold tracking-tight sm:text-5xl lg:text-6xl">
          모든 자산을{' '}
          <span className="bg-gradient-to-r from-blue-400 to-cyan-400 bg-clip-text text-transparent">한 곳에서</span>
        </h1>
        <p className="mx-auto max-w-xl text-lg text-gray-400 leading-relaxed">
          거래소, 지갑, 부동산, 수동 자산까지 — Allfolio 하나로 통합하고 6종 보고서로 내 포트폴리오를 깊이 분석하세요.
        </p>
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4 pt-2">
          <Link href="/login" className="w-full sm:w-auto rounded-xl bg-blue-600 px-8 py-3.5 text-sm font-semibold hover:bg-blue-500 transition-colors shadow-lg shadow-blue-900/30">
            무료로 시작하기 →
          </Link>
          <Link href="/login" className="w-full sm:w-auto rounded-xl border border-gray-600 px-8 py-3.5 text-sm font-semibold text-gray-300 hover:border-gray-400 hover:text-white transition-colors">
            로그인
          </Link>
        </div>
      </section>

      {/* Feature Grid */}
      <section className="space-y-8">
        <div className="text-center">
          <h2 className="text-2xl font-bold">주요 기능</h2>
          <p className="mt-2 text-sm text-gray-400">투자자에게 필요한 모든 것</p>
        </div>
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f) => (
            <div key={f.title} className="rounded-2xl border border-gray-800 bg-gray-900 p-6 hover:border-gray-600 transition-colors">
              <div className="text-3xl mb-3">{f.icon}</div>
              <h3 className="font-semibold text-gray-100">{f.title}</h3>
              <p className="mt-1.5 text-sm text-gray-500 leading-relaxed">{f.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* How it works */}
      <section className="rounded-2xl border border-gray-800 bg-gray-900 p-8 sm:p-12">
        <div className="text-center mb-10">
          <h2 className="text-2xl font-bold">3단계로 시작</h2>
        </div>
        <div className="grid gap-8 sm:grid-cols-3">
          {[
            { step: '01', title: '회원가입', desc: '이메일로 계정을 만드세요.' },
            { step: '02', title: '계좌 등록', desc: '거래소 API 키, 지갑 주소, 또는 수동으로 자산을 입력하세요.' },
            { step: '03', title: '보고서 확인', desc: 'Sync 한 번이면 6종 보고서가 자동으로 생성됩니다.' },
          ].map((s) => (
            <div key={s.step} className="text-center space-y-3">
              <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-blue-950 border border-blue-800 text-blue-400 font-mono font-bold text-sm">
                {s.step}
              </div>
              <h3 className="font-semibold">{s.title}</h3>
              <p className="text-sm text-gray-500">{s.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Bottom CTA */}
      <section className="text-center space-y-4 py-8">
        <h2 className="text-2xl font-bold">지금 바로 시작하세요</h2>
        <p className="text-gray-400 text-sm">무료, 광고 없음, 데이터는 내 서버에만 저장</p>
        <Link href="/login" className="inline-block rounded-xl bg-blue-600 px-8 py-3.5 text-sm font-semibold hover:bg-blue-500 transition-colors">
          무료 회원가입 →
        </Link>
      </section>
    </div>
  )
}
