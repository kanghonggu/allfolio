'use client'

import {
  createContext, useContext, useEffect, useState, useRef, useCallback,
  type ReactNode,
} from 'react'
import type Keycloak from 'keycloak-js'

interface KeycloakContextValue {
  keycloak:      Keycloak | null
  initialized:   boolean
  authenticated: boolean
  token:         string | null
  userName:      string | null
  userEmail:     string | null
  login:         () => void
  logout:        () => void
}

const KeycloakContext = createContext<KeycloakContextValue>({
  keycloak:      null,
  initialized:   false,
  authenticated: false,
  token:         null,
  userName:      null,
  userEmail:     null,
  login:         () => {},
  logout:        () => {},
})

export function KeycloakProvider({ children }: { children: ReactNode }) {
  const [initialized,   setInitialized]   = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [token,         setToken]         = useState<string | null>(null)
  const [userName,      setUserName]      = useState<string | null>(null)
  const [userEmail,     setUserEmail]     = useState<string | null>(null)
  const kcRef = useRef<Keycloak | null>(null)

  useEffect(() => {
    // SSR 방지 — 브라우저에서만 실행
    const { getKeycloak } = require('@/lib/keycloak')
    const kc: Keycloak = getKeycloak()
    kcRef.current = kc

    kc.init({
      onLoad:            'check-sso',
      silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
      pkceMethod:        'S256',
    }).then((auth) => {
      setAuthenticated(auth)
      setToken(kc.token ?? null)
      if (auth && kc.tokenParsed) {
        setUserName((kc.tokenParsed as any).name ?? (kc.tokenParsed as any).preferred_username ?? null)
        setUserEmail((kc.tokenParsed as any).email ?? null)
      }
      setInitialized(true)
    }).catch(() => setInitialized(true))

    // 토큰 자동 갱신 (55초마다 체크, 만료 30초 전에 refresh)
    const interval = setInterval(() => {
      kc.updateToken(30).then((refreshed) => {
        if (refreshed) setToken(kc.token ?? null)
      }).catch(() => kc.logout())
    }, 55_000)

    kc.onTokenExpired = () => {
      kc.updateToken(30).then((refreshed) => {
        if (refreshed) setToken(kc.token ?? null)
      }).catch(() => kc.logout())
    }

    return () => clearInterval(interval)
  }, [])

  const login  = useCallback(() => kcRef.current?.login(), [])
  const logout = useCallback(() => kcRef.current?.logout({ redirectUri: window.location.origin }), [])

  return (
    <KeycloakContext.Provider value={{
      keycloak: kcRef.current,
      initialized,
      authenticated,
      token,
      userName,
      userEmail,
      login,
      logout,
    }}>
      {children}
    </KeycloakContext.Provider>
  )
}

export function useKeycloak() {
  return useContext(KeycloakContext)
}
