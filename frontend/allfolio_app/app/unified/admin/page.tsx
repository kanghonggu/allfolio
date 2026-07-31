'use client'

import Link from 'next/link'
import { ADMIN_TOOLS } from './admin-tools'

/** 관리자 허브 (AF-11) — 관리자 도구 진입점. 가드는 상위 layout이 담당. */
export default function AdminHubPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">관리자 콘솔</h1>
        <p className="mt-1 text-sm text-gray-400">운영·마스터데이터 관리 도구 모음입니다 (ADMIN 전용)</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {ADMIN_TOOLS.map(tool => (
          <Link
            key={tool.href}
            href={tool.href}
            className="rounded-xl border border-gray-700 bg-gray-900 p-5 hover:border-amber-600 transition-colors"
          >
            <h2 className="font-semibold text-amber-400">{tool.title}</h2>
            <p className="mt-1.5 text-sm text-gray-400">{tool.desc}</p>
          </Link>
        ))}
      </div>
    </div>
  )
}
