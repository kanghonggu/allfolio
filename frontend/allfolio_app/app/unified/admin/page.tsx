'use client'

import Link from 'next/link'
import PageHeader from '@/components/ui/PageHeader'
import Label from '@/components/ui/Label'
import { ADMIN_TOOLS } from './admin-tools'

/** 관리자 허브 (AF-11) — 관리자 도구 진입점. 가드는 상위 layout이 담당. */
export default function AdminHubPage() {
  return (
    <div className="border border-line-card bg-surface">
      <PageHeader
        className="px-5 pt-5 sm:px-7"
        title="관리자 콘솔"
        meta={
          <>
            <Label size="sm" className="text-warn">ADMIN</Label>
            <span className="ml-3">운영·마스터데이터 관리 도구 모음</span>
          </>
        }
      />

      <div className="px-5 py-5 pb-10 sm:px-7">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {ADMIN_TOOLS.map(tool => (
            <Link
              key={tool.href}
              href={tool.href}
              className="border border-line bg-surface p-5 transition-colors hover:border-warn"
            >
              <h2 className="m-0 text-[14px] font-medium">{tool.title}</h2>
              <p className="mt-1.5 text-xs leading-relaxed text-fg-3">{tool.desc}</p>
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}
