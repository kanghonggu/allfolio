/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  async rewrites() {
    return [
      {
        source: '/api/:path((?!auth/).*)',
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
