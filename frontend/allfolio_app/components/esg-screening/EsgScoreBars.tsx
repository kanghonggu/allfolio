// components/esg-screening/EsgScoreBars.tsx
import Num from '@/components/ui/Num'
import SectionHeader from '@/components/ui/SectionHeader'
import type { EsgScores } from '@/types/esg-screening'

export function EsgScoreBars({ esg }: { esg: EsgScores }) {
  return (
    <section className="break-inside-avoid">
      <SectionHeader label="E·S·G 점수" />
      <div className="space-y-4 border-t-[1.5px] border-ink pt-4">
        <ScoreBar label="환경 (E)" score={esg.environmental} color="bg-ink" />
        <ScoreBar label="사회 (S)" score={esg.social} color="bg-fg-muted" />
        <ScoreBar label="지배구조 (G)" score={esg.governance} color="bg-fg-ghost" />
        <div className="border-t border-line pt-3">
          <ScoreBar label="종합" score={esg.totalScore} color="bg-ink" />
        </div>
      </div>
    </section>
  )
}

function ScoreBar({ label, score, color }: { label: string; score: number; color: string }) {
  const pct = Math.min(100, Math.max(0, score))
  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between text-[13px]">
        <span className="text-fg-2">{label}</span>
        <Num className="text-[12.5px] font-medium text-ink">{score.toFixed(1)}점</Num>
      </div>
      <div className="h-1.5 bg-line-hair">
        <div className={`h-1.5 ${color}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}
