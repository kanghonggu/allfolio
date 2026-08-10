'use client'

import {
  createContext, useContext, useEffect, useState, useCallback,
  type ReactNode,
} from 'react'
import { setApiAccessToken } from '@/lib/api'
import { navigateWithFreshSession } from '@/lib/session'

interface AuthState {
  accessToken:  string | null
  expiresAt:    number | null   // ms timestamp
  userName:     string | null
  userEmail:    string | null
  userId:       string | null
  role:         string | null
}

interface AuthContextValue extends AuthState {
  initialized:   boolean
  authenticated: boolean
  isAdmin: boolean
  login:  (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName?: string) => Promise<void>
  logout: () => void
}

// QA P0 #5: refreshToken은 HttpOnly 쿠키(allfolio_rt)로만 오가고,
// accessToken은 메모리에만 유지한다 — localStorage 토큰 저장 제거(XSS 탈취 방지).
// 인증 경로는 Next rewrite 프록시(/api/auth/*)를 거쳐 same-origin으로 쿠키가 흐른다.
const LOGIN_URL    = '/api/auth/login'
const REGISTER_URL = '/api/auth/register'
const REFRESH_URL  = '/api/auth/refresh'
const LOGOUT_URL   = '/api/auth/logout'

const EMPTY_STATE: AuthState = {
  accessToken: null, expiresAt: null,
  userName: null, userEmail: null, userId: null, role: null,
}

const AuthContext = createContext<AuthContextValue>({
  ...EMPTY_STATE,
  initialized: false, authenticated: false, isAdmin: false,
  login: async () => {}, register: async () => {}, logout: () => {},
})

interface AuthApiResponse {
  accessToken: string
  expiresIn: number
  user: {
    id: string
    email: string
    displayName: string | null
    role: string
  }
}

function toAuthState(data: AuthApiResponse): AuthState {
  return {
    accessToken:  data.accessToken,
    expiresAt:    Date.now() + data.expiresIn * 1000,
    userName:     data.user.displayName ?? data.user.email,
    userEmail:    data.user.email,
    userId:       data.user.id,
    role:         data.user.role,
  }
}

async function requestToken(url: string, body?: Record<string, string>): Promise<AuthState> {
  const res = await fetch(url, {
    method: 'POST',
    ...(body ? {
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    } : {}),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error ?? err.message ?? '인증 실패')
  }
  return toAuthState(await res.json())
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state,       setState]       = useState<AuthState>(EMPTY_STATE)
  const [initialized, setInitialized] = useState(false)

  // 구버전 API 클라이언트(lib/api.ts)에 메모리 토큰 전파
  useEffect(() => {
    setApiAccessToken(state.accessToken)
  }, [state.accessToken])

  // 앱 시작 시 HttpOnly 쿠키 기반 silent refresh로 세션 복구
  useEffect(() => {
    requestToken(REFRESH_URL)
      .then(setState)
      .catch(() => setState(EMPTY_STATE))
      .finally(() => setInitialized(true))
  }, [])

  // 만료 1분 전 자동 갱신 (쿠키가 refresh token을 실어 보낸다)
  useEffect(() => {
    if (!state.accessToken || !state.expiresAt) return
    const delay = state.expiresAt - Date.now() - 60_000
    if (delay <= 0) return
    const t = setTimeout(() => {
      requestToken(REFRESH_URL)
        .then(setState)
        .catch(logout)
    }, delay)
    return () => clearTimeout(t)
  }, [state.accessToken, state.expiresAt])

  const login = useCallback(async (email: string, password: string) => {
    setState(await requestToken(LOGIN_URL, { email, password }))
  }, [])

  const register = useCallback(async (email: string, password: string, displayName?: string) => {
    const body: Record<string, string> = { email, password }
    const trimmedName = displayName?.trim()
    if (trimmedName) body.displayName = trimmedName
    setState(await requestToken(REGISTER_URL, body))
  }, [])

  // QA AF-87: 뒤로가기로 bfcache에서 복원된 문서는 이전 세션의 JS 힙(메모리 토큰·
  // 렌더된 자산 데이터)을 그대로 되살린다. 복원된 경우 강제로 다시 로드한다.
  useEffect(() => {
    const onPageShow = (e: PageTransitionEvent) => {
      if (e.persisted) window.location.reload()
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  const logout = useCallback(async () => {
    setState(EMPTY_STATE)
    // 서버에서 refresh token revoke + 쿠키 삭제. 이어지는 하드 내비게이션이 요청을
    // 끊지 않도록 완료를 기다리고 keepalive도 함께 건다 (실패해도 로컬 세션은 비운다)
    await fetch(LOGOUT_URL, { method: 'POST', keepalive: true }).catch(() => {})
    // 클라이언트 캐시까지 확실히 버리기 위해 하드 내비게이션으로 로그인 화면 이동
    navigateWithFreshSession('/login')
  }, [])

  return (
    <AuthContext.Provider value={{
      ...state,
      initialized,
      authenticated: !!state.accessToken,
      isAdmin: state.role === 'ADMIN',
      login,
      register,
      logout,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
