import type { NextAuthOptions } from 'next-auth'
import KeycloakProvider from 'next-auth/providers/keycloak'

declare module 'next-auth' {
  interface Session {
    accessToken: string
    userId: string
    error?: string
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    accessToken: string
    refreshToken: string
    accessTokenExpires: number
    userId: string
    error?: string
  }
}

async function refreshAccessToken(token: any) {
  try {
    const url = `${process.env.KEYCLOAK_ISSUER}/protocol/openid-connect/token`
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type:    'refresh_token',
        client_id:     process.env.KEYCLOAK_CLIENT_ID!,
        client_secret: process.env.KEYCLOAK_CLIENT_SECRET!,
        refresh_token: token.refreshToken,
      }),
    })
    const refreshed = await res.json()
    if (!res.ok) throw refreshed
    return {
      ...token,
      accessToken:        refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      refreshToken:       refreshed.refresh_token ?? token.refreshToken,
    }
  } catch {
    return { ...token, error: 'RefreshAccessTokenError' }
  }
}

export const authOptions: NextAuthOptions = {
  debug: true,
  providers: [
    KeycloakProvider({
      clientId:     process.env.KEYCLOAK_CLIENT_ID!,
      clientSecret: process.env.KEYCLOAK_CLIENT_SECRET!,
      issuer:       process.env.KEYCLOAK_ISSUER!,
      checks:       ['state'],
    }),
  ],
  callbacks: {
    async jwt({ token, account, profile }) {
      // 최초 로그인
      if (account) {
        return {
          ...token,
          accessToken:        account.access_token!,
          refreshToken:       account.refresh_token!,
          accessTokenExpires: account.expires_at! * 1000,
          userId:             (profile as any)?.sub ?? token.sub!,
        }
      }
      // 토큰 유효
      if (Date.now() < token.accessTokenExpires) return token
      // 토큰 만료 → refresh
      return refreshAccessToken(token)
    },
    async session({ session, token }) {
      session.accessToken = token.accessToken
      session.userId      = token.userId
      session.error       = token.error
      return session
    },
  },
  pages: {
    signIn: '/auth/signin',
  },
}
