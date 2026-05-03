import Keycloak from 'keycloak-js'

// 싱글톤 — 클라이언트 사이드에서만 생성
let _keycloak: Keycloak | null = null

export function getKeycloak(): Keycloak {
  if (!_keycloak) {
    _keycloak = new Keycloak({
      url:      process.env.NEXT_PUBLIC_KEYCLOAK_URL!,
      realm:    process.env.NEXT_PUBLIC_KEYCLOAK_REALM!,
      clientId: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID!,
    })
  }
  return _keycloak
}
