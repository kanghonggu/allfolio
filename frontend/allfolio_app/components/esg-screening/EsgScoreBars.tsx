// components/esg-screening/EsgScoreBars.tsx
import type { EsgScores } from '@/types/esg-screening'

export function EsgScoreBars({ esg }: { esg: EsgScores }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">E·S·G 점수</h2>
      <div className="space-y-4 rounded-xl border border-gray-700 bg-gray-900 p-5">
        <ScoreBar label="환경 (E)" score={esg.environmental} color="bg-emerald-500" />
        <ScoreBar label="사회 (S)" score={esg.social} color="bg-sky-500" />
        <ScoreBar label="지배구조 (G)" score={esg.governance} color="bg-violet-500" />
        <div className="border-t border-gray-800 pt-3">
          <ScoreBar label="종합" score={esg.totalScore} color="bg-amber-500" />
        </div>
      </div>
    </section>
  )
}

function ScoreBar({ label, score, color }: { label: string; score: number; color: string }) {
  const pct = Math.min(100, Math.max(0, score))
  return (
    <div>
      <div className="mb-1 flex justify-between text-sm">
        <span className="text-gray-300">{label}</span>
        <span className="tabular-nums font-medium text-gray-100">{score.toFixed(1)}점</span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-gray-800">
        <div className={`h-2 rounded-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
