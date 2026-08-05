'use client'

import Link from 'next/link'
import { useState, useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import Button from '@/components/ui/Button'
import Field, { Input } from '@/components/ui/Field'

export default function LoginPage() {
  const { login, authenticated, initialized } = useAuth()
  const router = useRouter()

  const [email,    setEmail]    = useState('')
  const [password, setPassword] = useState('')
  const [loading,  setLoading]  = useState(false)
  const [error,    setError]    = useState<string | null>(null)
  const [showPw,   setShowPw]   = useState(false)

  useEffect(() => {
    if (initialized && authenticated) router.replace('/unified')
  }, [initialized, authenticated, router])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(email.trim(), password)
      router.replace('/unified')
    } catch (err: any) {
      setError(err.message ?? '로그인에 실패했습니다')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-canvas px-4 py-10">
      <div className="mx-auto w-full max-w-md border border-line-card bg-surface p-6 sm:p-8">

        {/* 워드마크 + 제목 */}
        <div className="border-b border-line pb-5">
          <span className="font-mono text-[10px] tracking-brand text-fg-muted">ALLFOLIO</span>
          <h1 className="m-0 mt-2.5 font-serif text-[22px] font-semibold tracking-[-0.01em]">로그인</h1>
          <p className="mt-1.5 text-xs text-fg-faint">모든 자산을 한 곳에서 — 계정 정보를 입력하세요</p>
        </div>

        {/* 로그인 폼 */}
        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          {error && (
            <p role="alert" className="m-0 border border-danger px-4 py-3 text-xs text-danger">
              {error}
            </p>
          )}

          <Field id="login-email" label="이메일 또는 아이디">
            <Input
              type="text"
              value={email}
              onChange={e => setEmail(e.target.value)}
              required
              autoComplete="username"
              placeholder="name@example.com"
            />
          </Field>

          <div>
            <label htmlFor="login-password" className="mb-1.5 block font-mono text-[10px] tracking-label text-fg-muted">
              비밀번호
            </label>
            <div className="relative">
              <Input
                id="login-password"
                type={showPw ? 'text' : 'password'}
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
                autoComplete="current-password"
                placeholder="••••••••"
                className="pr-10"
              />
              <button
                type="button"
                onClick={() => setShowPw(v => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-fg-faint transition-colors hover:text-ink"
                tabIndex={-1}
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

          <Button type="submit" variant="primary" disabled={loading} className="w-full">
            {loading ? '로그인 중…' : '로그인'}
          </Button>
        </form>

        <p className="mt-6 border-t border-line-hair pt-4 text-center text-xs text-fg-faint">
          계정이 없으신가요?{' '}
          <Link href="/register" className="text-link transition-colors hover:text-link-hover">
            회원가입
          </Link>
        </p>
      </div>
    </div>
  )
}
