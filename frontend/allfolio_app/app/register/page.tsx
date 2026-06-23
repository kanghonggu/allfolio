'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useEffect, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'

export default function RegisterPage() {
  const { register, authenticated, initialized } = useAuth()
  const router = useRouter()

  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (initialized && authenticated) router.replace('/unified')
  }, [initialized, authenticated, router])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    const trimmedEmail = email.trim()
    if (!trimmedEmail.includes('@')) {
      setError('이메일 형식이 올바르지 않습니다')
      return
    }
    if (password.length < 8) {
      setError('비밀번호는 8자 이상이어야 합니다')
      return
    }
    if (password !== confirmPassword) {
      setError('비밀번호 확인이 일치하지 않습니다')
      return
    }

    setLoading(true)
    try {
      await register(trimmedEmail, password, displayName)
      router.replace('/unified')
    } catch (err: any) {
      setError(err.message ?? '회원가입에 실패했습니다')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-950 px-4 py-10">
      <div className="w-full max-w-sm space-y-8">
        <div className="space-y-2 text-center">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-blue-600 shadow-lg shadow-blue-900/40">
            <svg className="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M3 13.5l4-4 4 4 4-6 4 4" />
              <path strokeLinecap="round" strokeLinejoin="round" d="M3 20h18M3 4h18" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold text-white">Allfolio</h1>
          <p className="text-sm text-gray-400">투자 성과 리포트를 위한 계정을 만드세요</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5 rounded-2xl border border-gray-800 bg-gray-900 p-8">
          <div className="space-y-1">
            <h2 className="text-base font-semibold text-white">회원가입</h2>
            <p className="text-xs text-gray-500">이메일과 비밀번호로 시작하세요</p>
          </div>

          {error && (
            <div className="rounded-lg border border-red-800/50 bg-red-900/20 px-4 py-3 text-sm text-red-400">
              {error}
            </div>
          )}

          <div className="space-y-4">
            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-gray-400">이메일</label>
              <input
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                required
                autoComplete="email"
                placeholder="name@example.com"
                className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-600 outline-none transition-all focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
              />
            </div>

            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-gray-400">표시 이름</label>
              <input
                type="text"
                value={displayName}
                onChange={e => setDisplayName(e.target.value)}
                autoComplete="name"
                placeholder="선택 입력"
                className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-600 outline-none transition-all focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
              />
            </div>

            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-gray-400">비밀번호</label>
              <div className="relative">
                <input
                  type={showPw ? 'text' : 'password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                  minLength={8}
                  autoComplete="new-password"
                  placeholder="8자 이상"
                  className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 pr-10 text-sm text-white placeholder-gray-600 outline-none transition-all focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
                />
                <button
                  type="button"
                  onClick={() => setShowPw(v => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300"
                  aria-label={showPw ? '비밀번호 숨기기' : '비밀번호 보기'}
                >
                  {showPw ? (
                    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88" />
                    </svg>
                  ) : (
                    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" />
                      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                  )}
                </button>
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="block text-xs font-medium text-gray-400">비밀번호 확인</label>
              <input
                type={showPw ? 'text' : 'password'}
                value={confirmPassword}
                onChange={e => setConfirmPassword(e.target.value)}
                required
                minLength={8}
                autoComplete="new-password"
                placeholder="비밀번호 재입력"
                className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-600 outline-none transition-all focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-blue-600 py-3 text-sm font-semibold text-white transition-colors hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loading ? (
              <><div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" /> 가입 중...</>
            ) : '회원가입'}
          </button>
        </form>

        <p className="text-center text-xs text-gray-600">
          이미 계정이 있으신가요?{' '}
          <Link href="/login" className="text-blue-400 hover:text-blue-300">
            로그인
          </Link>
        </p>
      </div>
    </div>
  )
}
