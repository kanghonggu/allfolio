import type { Position } from '@/types/portfolio'

function fmt(n: number, d = 6) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: 0,
    maximumFractionDigits: d,
  })
}

function shortId(uuid: string) {
  return uuid.slice(0, 8).toUpperCase()
}

type Props = { positions: Position[] }

export default function PositionList({ positions }: Props) {
  if (positions.length === 0) {
    return <p className="text-sm text-gray-500">포지션 없음</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-700 text-left text-xs text-gray-400">
            <th className="pb-2 pr-4">자산 ID</th>
            <th className="pb-2 pr-4 text-right">수량</th>
            <th className="pb-2 pr-4 text-right">원가단가</th>
            <th className="pb-2 pr-4 text-right">통화</th>
            <th className="pb-2 text-right">평가금액(KRW)</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800">
          {positions.map((pos) => (
            <tr key={pos.assetId} className="hover:bg-gray-800/50">
              <td className="py-2 pr-4 font-mono text-gray-300">
                <span title={pos.assetId}>{shortId(pos.assetId)}</span>
              </td>
              <td className="py-2 pr-4 text-right font-mono">{fmt(pos.quantity)}</td>
              <td className="py-2 pr-4 text-right font-mono">
                {fmt(pos.costBasis, 2)}
                <span className="ml-1 text-[10px] text-gray-500">{pos.costMethod}</span>
              </td>
              <td className="py-2 pr-4 text-right text-gray-400">{pos.currency}</td>
              <td className="py-2 text-right font-mono">{fmt(pos.krwValue, 0)}원</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
