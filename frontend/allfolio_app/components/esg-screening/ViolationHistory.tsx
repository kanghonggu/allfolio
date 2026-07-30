import type { EsgViolationEvent } from '@/types/esg-screening'

const EVENT_STYLE: Record<string, string> = {
  편입: 'bg-red-950 text-red-300',
  청산: 'bg-gray-800 text-gray-300',
  리스트등록: 'bg-amber-950 text-amber-300',
}

export function ViolationHistory({ events }: { events: EsgViolationEvent[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">위반 이력</h2>
      {events.length === 0 ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">
          위반 이력 없음
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-gray-800 bg-gray-900">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="p-3">일자</th><th className="p-3">종목</th><th className="p-3">이벤트</th><th className="p-3">비고</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e, i) => (
                <tr key={`${e.symbol}-${e.date}-${e.event}-${i}`} className="border-b border-gray-800 last:border-b-0">
                  <td className="p-3 tabular-nums text-gray-400">{e.date}</td>
                  <td className="p-3">
                    <span className="text-gray-100">{e.name}</span>
                    <span className="ml-2 text-xs text-gray-500">{e.symbol}</span>
                  </td>
                  <td className="p-3">
                    <span className={`rounded px-2 py-0.5 text-xs ${EVENT_STYLE[e.event] ?? 'bg-gray-800 text-gray-300'}`}>{e.event}</span>
                  </td>
                  <td className="p-3 text-gray-500">{e.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
