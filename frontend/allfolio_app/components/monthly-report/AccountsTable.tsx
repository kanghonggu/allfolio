// components/monthly-report/AccountsTable.tsx
import type { AccountRow } from '@/types/monthly-report'
import { fmtKrw } from '@/lib/report-format'

export function AccountsTable({ accounts }: { accounts: AccountRow[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">계좌별 현황</h2>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">계좌</th><th className="p-3">증권사</th>
              <th className="p-3 text-right">평가액</th><th className="p-3 text-right">비중</th>
              <th className="p-3 text-right">자산수</th>
            </tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={`${a.provider}-${a.accountName}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 font-medium text-gray-100">{a.accountName}</td>
                <td className="p-3 text-gray-400">{a.provider}</td>
                <td className="p-3 text-right tabular-nums">{fmtKrw(a.valueKrw)}</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{a.weight.toFixed(2)}%</td>
                <td className="p-3 text-right tabular-nums text-gray-300">{a.assetCount}</td>
              </tr>
            ))}
            {accounts.length === 0 && (
              <tr><td colSpan={5} className="p-4 text-center text-gray-500">계좌가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}
