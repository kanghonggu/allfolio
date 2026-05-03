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
  logout: () => void
}

const STORAGE_KEY = 'allfolio_auth'
const KC_URL      = process.env.NEXT_PUBLIC_KEYCLOAK_URL!
const KC_REALM    = process.env.NEXT_PUBLIC_KEYCLOAK_REALM!
const KC_CLIENT   = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID!
const TOKEN_URL   = `${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token`

const AuthContext = createContext<AuthContextValue>({
  accessToken: null, refreshToken: null, expiresAt: null,
  userName: null, userEmail: null, userId: null,
  initialized: false, authenticated: false,
  login: async () => {}, logout: () => {},
})

function parseToken(jwt: string) {
  try {
    const payload = JSON.parse(atob(jwt.split('.')[1]))
    return {
      userName:  payload.name ?? payload.preferred_username ?? null,
      userEmail: payload.email ?? null,
      userId:    payload.sub ?? null,
    }
  } catch { return { userName: null, userEmail: null, userId: null } }
}

async function fetchToken(params: Record<string, string>): Promise<AuthState> {
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: KC_CLIENT, ...params }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error_description ?? '인증 실패')
  }
  const data = await res.json()
  const { userName, userEmail, userId } = parseToken(data.access_token)
  return {
    accessToken:  data.access_token,
    refreshToken: data.refresh_token,
    expiresAt:    Date.now() + data.expires_in * 1000,
    userName,
    userEmail,
    userId,
  }
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
    fetchToken({ grant_type: 'refresh_token', refresh_token: saved.refreshToken })
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
      fetchToken({ grant_type: 'refresh_token', refresh_token: state.refreshToken! })
        .then(next => {
          setState(next)
          localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
        })
        .catch(logout)
    }, delay)
    return () => clearTimeout(t)
  }, [state.refreshToken, state.expiresAt])

  const login = useCallback(async (email: string, password: string) => {
    const next = await fetchToken({
      grant_type: 'password',
      username:   email,
      password,
    })
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
      logout,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
