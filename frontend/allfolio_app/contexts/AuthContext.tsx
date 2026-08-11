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
// 백엔드가 아니라 Next(Vercel)에서 도는 라우트 — 백엔드가 죽어도 쿠키를 지운다 (AF-96)
const SESSION_CLEAR_URL = '/api/session/clear'

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

/**
 * 로그아웃 revoke 재시도 간격 (AF-96).
 *
 * revoke가 실패하면 refresh token이 서버에 살아남고, 쿠키는 HttpOnly라 프론트가
 * 지울 수 없다. 라이브에서 관측된 실패(503)는 Render 무료 플랜이 잠들어 있을 때
 * 프록시가 upstream에 닿지 못한 것으로 보이는데, 그 첫 요청이 인스턴스를 깨우므로
 * 다음 시도는 성공할 가능성이 높다.
 *
 * 다만 완전한 콜드스타트(~85초)까지 덮지는 못한다 — 로그아웃하려는 사람을 그만큼
 * 붙잡아 둘 수 없어 상한을 둔다. 이 상한을 넘긴 실패는 경고만 남기고 진행한다.
 */
const LOGOUT_RETRY_DELAYS_MS = [500, 1500]

/** 성공하면 true. 네트워크 오류·5xx 모두 재시도 대상. */
async function revokeSession(): Promise<boolean> {
  for (let attempt = 0; ; attempt++) {
    try {
      // keepalive: 이어지는 하드 내비게이션이 마지막 요청을 끊지 않게 한다
      const res = await fetch(LOGOUT_URL, { method: 'POST', keepalive: true })
      if (res.ok) return true
    } catch {
      // 네트워크 자체가 실패한 경우도 같은 재시도 경로를 탄다
    }
    if (attempt >= LOGOUT_RETRY_DELAYS_MS.length) return false
    await new Promise((r) => setTimeout(r, LOGOUT_RETRY_DELAYS_MS[attempt]))
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
    // 서버에서 refresh token revoke + 쿠키 삭제
    const revoked = await revokeSession()
    if (!revoked) {
      console.warn('[auth] 서버 세션 무효화에 실패했습니다 — 쿠키만 정리하고 이동합니다')
    }
    // revoke 성공 여부와 무관하게 한 번 더 지운다. 실패했다면 이게 유일한 방어선이고,
    // 성공했다면 같은 쿠키를 두 번 지우는 것뿐이라 부작용이 없다.
    await fetch(SESSION_CLEAR_URL, { method: 'POST', keepalive: true }).catch(() => {})
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
