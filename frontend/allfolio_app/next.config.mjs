/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  async rewrites() {
    // /api/auth/* 포함 전체 프록시 — refresh 쿠키(HttpOnly)가 same-origin으로 흐르게 한다 (QA P0 #5)
    return [
      {
        source: '/api/:path*',
        destination: `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/:path*`,
      },
    ]
  },
  experimental: {
    serverActions: {
      bodySizeLimit: '10mb',
    },
  },
}

export default nextConfig
