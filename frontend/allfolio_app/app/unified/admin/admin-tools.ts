/** 관리자 도구 목록 — 허브 카드·서브내비 공용 (AF-11, MN-600~800). */
export const ADMIN_TOOLS = [
  { href: '/unified/admin/closing',           title: '마감 대시보드', desc: '일일마감 워크플로우 현황·수동 개입·재작업 이력' },
  { href: '/unified/admin/ops',               title: '운영 모니터링', desc: 'Outbox 이벤트·DLQ 현황과 DEAD 건 재처리' },
  { href: '/unified/admin/tax-rates',         title: '세율 마스터',   desc: '국가×소득유형 원천징수 세율 관리 (유효기간 버저닝)' },
  { href: '/unified/admin/exclusion-presets', title: '배제 프리셋',   desc: 'ESG 스크리닝 내장 배제 프리셋 큐레이션' },
] as const
