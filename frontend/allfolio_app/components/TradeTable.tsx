import type { Trade } from '@/types/trade'

function fmt(n: number, d = 2) {
  return n.toLocaleString('ko-KR', {
    minimumFractionDigits: d,
    maximumFractionDigits: d,
  })
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', {
    year: '2-digit', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function shortId(uuid: string) {
  return uuid.slice(0, 8).toUpperCase()
}

type Props = { trades: Trade[] }

export default function TradeTable({ trades }: Props) {
  if (trades.length === 0) {
    return <p className="text-sm text-gray-500">거래 내역 없음</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-700 text-left text-xs text-gray-400">
            <th className="pb-2 pr-4">체결일시</th>
            <th className="pb-2 pr-4">자산 ID</th>
            <th className="pb-2 pr-4">구분</th>
            <th className="pb-2 pr-4 text-right">수량</th>
            <th className="pb-2 pr-4 text-right">단가</th>
            <th className="pb-2 pr-4 text-right">체결금액</th>
            <th className="pb-2 pr-4 text-right">수수료</th>
            <th className="pb-2 text-right">브로커</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-800">
          {trades.map((t) => (
            <tr key={t.tradeId} className="hover:bg-gray-800/50">
              <td className="py-2 pr-4 font-mono text-xs text-gray-400">
                {fmtDate(t.executedAt)}
              </td>
              <td className="py-2 pr-4 font-mono text-gray-300">
                <span title={t.assetId}>{shortId(t.assetId)}</span>
              </td>
              <td className="py-2 pr-4">
                <span
                  className={`rounded px-1.5 py-0.5 text-xs font-semibold ${
                    t.tradeType === 'BUY'
                      ? 'bg-red-900/50 text-red-400'
                      : 'bg-blue-900/50 text-blue-400'
                  }`}
                >
                  {t.tradeType === 'BUY' ? '매수' : '매도'}
                </span>
              </td>
              <td className="py-2 pr-4 text-right font-mono">{fmt(t.quantity, 6)}</td>
              <td className="py-2 pr-4 text-right font-mono">{fmt(t.price)}</td>
              <td className="py-2 pr-4 text-right font-mono">
                {fmt(t.quantity * t.price)}
              </td>
              <td className="py-2 pr-4 text-right font-mono text-gray-400">
                {fmt(t.fee)}
              </td>
              <td className="py-2 text-right text-xs text-gray-500">
                {t.brokerType ?? '-'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
