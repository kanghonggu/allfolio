export { default } from 'next-auth/middleware'

export const config = {
  // /unified/* 와 /portfolio/* 는 로그인 필요
  matcher: ['/unified/:path*', '/portfolio/:path*', '/trades/:path*'],
}
