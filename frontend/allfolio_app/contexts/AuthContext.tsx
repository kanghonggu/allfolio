'use client'

import {
  createContext, useContext, useEffect, useState, useCallback,
  type ReactNode,
} from 'react'

interface AuthState {
  accessToken:  string | null
  refreshToken: string | null
  expiresAt:    number | null   // ms timestamp
  userName:     string | null
  userEmail:    string | null
  userId:       string | null
}

interface AuthContextValue extends AuthState {
  initialized:   boolean
  authenticated: boolean
  login:  (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName?: string) => Promise<void>
  logout: () => void
}

const STORAGE_KEY = 'allfolio_auth'
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'
const LOGIN_URL    = `${API_BASE_URL}/api/auth/login`
const REGISTER_URL = `${API_BASE_URL}/api/auth/register`
const REFRESH_URL  = `${API_BASE_URL}/api/auth/refresh`

const AuthContext = createContext<AuthContextValue>({
  accessToken: null, refreshToken: null, expiresAt: null,
  userName: null, userEmail: null, userId: null,
  initialized: false, authenticated: false,
  login: async () => {}, register: async () => {}, logout: () => {},
})

interface AuthApiResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: {
    id: string
    email: string
    displayName: string | null
  }
}

function toAuthState(data: AuthApiResponse): AuthState {
  return {
    accessToken:  data.accessToken,
    refreshToken: data.refreshToken,
    expiresAt:    Date.now() + data.expiresIn * 1000,
    userName:     data.user.displayName ?? data.user.email,
    userEmail:    data.user.email,
    userId:       data.user.id,
  }
}

async function requestToken(url: string, body: Record<string, string>): Promise<AuthState> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error ?? err.message ?? '인증 실패')
  }
  return toAuthState(await res.json())
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state,       setState]       = useState<AuthState>({
    accessToken: null, refreshToken: null, expiresAt: null,
    userName: null, userEmail: null, userId: null,
  })
  const [initialized, setInitialized] = useState(false)

  // 앱 시작 시 localStorage에서 복구 + silent refresh
  useEffect(() => {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) { setInitialized(true); return }

    const saved: AuthState = JSON.parse(raw)
    if (!saved.refreshToken) { setInitialized(true); return }

    // refresh token으로 새 access token 발급
    requestToken(REFRESH_URL, { refreshToken: saved.refreshToken })
      .then(next => {
        setState(next)
        localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      })
      .catch(() => localStorage.removeItem(STORAGE_KEY))
      .finally(() => setInitialized(true))
  }, [])

  // 만료 1분 전 자동 갱신
  useEffect(() => {
    if (!state.refreshToken || !state.expiresAt) return
    const delay = state.expiresAt - Date.now() - 60_000
    if (delay <= 0) return
    const t = setTimeout(() => {
      requestToken(REFRESH_URL, { refreshToken: state.refreshToken! })
        .then(next => {
          setState(next)
          localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
        })
        .catch(logout)
    }, delay)
    return () => clearTimeout(t)
  }, [state.refreshToken, state.expiresAt])

  const login = useCallback(async (email: string, password: string) => {
    const next = await requestToken(LOGIN_URL, { email, password })
    setState(next)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  }, [])

  const register = useCallback(async (email: string, password: string, displayName?: string) => {
    const body: Record<string, string> = { email, password }
    const trimmedName = displayName?.trim()
    if (trimmedName) body.displayName = trimmedName

    const next = await requestToken(REGISTER_URL, body)
    setState(next)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  }, [])

  const logout = useCallback(() => {
    setState({ accessToken: null, refreshToken: null, expiresAt: null, userName: null, userEmail: null, userId: null })
    localStorage.removeItem(STORAGE_KEY)
  }, [])

  return (
    <AuthContext.Provider value={{
      ...state,
      initialized,
      authenticated: !!state.accessToken,
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
