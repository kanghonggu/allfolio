'use client'

import type { Position } from '@/types/dashboard'

const TYPE_COLORS: Record<string, string> = {
  CRYPTO: '#f59e0b', STOCK: '#3b82f6', GOLD: '#eab308',
  CASH: '#6b7280', ETC: '#ec4899',
}
const TYPE_KO: Record<string, string> = {
  CRYPTO: '코인', STOCK: '주식', GOLD: '금', CASH: '현금', ETC: '기타',
}

interface PositionTableProps {
  positions: Position[]
}

export default function PositionTable({ positions }: PositionTableProps) {
  if (positions.length === 0) {
    return (
      <div className="rounded-xl border border-gray-700 bg-gray-900 py-12 text-center text-sm text-gray-500">
        투자 포지션 없음
      </div>
    )
  }

  return (
    <div className="rounded-xl border border-gray-700 bg-gray-900 overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-700">
        <h3 className="text-sm font-semibold text-gray-300">포지션 ({positions.length})</h3>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
              <th className="px-6 py-3 font-medium">자산명</th>
              <th className="px-4 py-3 font-medium">유형</th>
              <th className="px-4 py-3 text-right font-medium">평가액</th>
              <th className="px-4 py-3 text-right font-medium">수익률</th>
              <th className="px-4 py-3 text-right font-medium">비중</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-800">
            {positions.map((p) => {
              const ret = p.returnRate
              const color = TYPE_COLORS[p.type] ?? '#9ca3af'
              return (
                <tr key={p.id} className="hover:bg-gray-800/50 transition-colors">
                  <td className="px-6 py-3">
                    <div className="font-medium text-gray-100">{p.name}</div>
                    {p.symbol && <div className="text-xs text-gray-500">{p.symbol}</div>}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className="rounded-full px-2 py-0.5 text-xs font-medium"
                      style={{ background: `${color}20`, color }}
                    >
                      {TYPE_KO[p.type] ?? p.type}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-200">
                    ₩{p.currentValue.toLocaleString('ko-KR')}
                  </td>
                  <td className={`px-4 py-3 text-right tabular-nums ${ret >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                    {ret >= 0 ? '+' : ''}{ret.toFixed(2)}%
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums text-gray-400">
                    {(p.weight * 100).toFixed(1)}%
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
