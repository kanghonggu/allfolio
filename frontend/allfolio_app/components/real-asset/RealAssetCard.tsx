// components/real-asset/RealAssetCard.tsx
'use client'

import type { RealAssetView } from '@/types/real-asset'
import Panel from '@/components/ui/Panel'
import Badge from '@/components/ui/Badge'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { dirTone, signWon, won } from '@/lib/format'
import {
  ASSET_TYPE_LABEL,
  SUB_TYPE_LABEL,
  gramsWithDon,
  priceAsOfLabel,
  ratePct,
  stalenessTone,
} from '@/lib/real-asset-format'

/**
 * 실물자산 한 건.
 *
 * **평가 전 상태를 0원으로 그리지 않는다.** 등록 당일이나 배치 전에는 서버가 평가 필드를
 * `null`로 주는데, 그걸 0으로 접으면 "평가액 ₩0 · 전액 손실"이 된다. 사실은 **아직 모른다**는
 * 뜻이라 그렇게 말해야 한다.
 *
 * **기준일을 반드시 노출한다** (설계 1절 원칙 2). 일요일에 들어온 사용자가 보는 숫자는
 * 금요일 종가다. 숨기면 사용자는 그 값이 지금 시세인 줄 안다.
 */
export default function RealAssetCard({ asset }: { asset: RealAssetView }) {
  const valued = asset.valuationKrw !== null
  const stale = stalenessTone(asset.stalenessDays) === 'stale'

  return (
    <Panel className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="text-[14px] font-semibold text-ink">{asset.name}</div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-fg-faint">
            <span>{ASSET_TYPE_LABEL[asset.assetType] ?? asset.assetType}</span>
            {asset.subType && <span>· {SUB_TYPE_LABEL[asset.subType] ?? asset.subType}</span>}
            {/* 돈 병기 — 한국에서 금 무게는 돈으로 말한다 */}
            <span>· {gramsWithDon(asset.quantity)}</span>
          </div>
        </div>

        <div className="text-right">
          {valued ? (
            <>
              <Num className="text-[17px] font-semibold">{won(asset.valuationKrw)}</Num>
              <div className="mt-0.5">
                <Num tone={dirTone(asset.profitKrw)} className="text-[12px]">
                  {signWon(asset.profitKrw)}
                </Num>
                {asset.profitRate !== null && (
                  <Num tone={dirTone(asset.profitKrw)} className="ml-1.5 text-[12px]">
                    {ratePct(asset.profitRate)}
                  </Num>
                )}
              </div>
            </>
          ) : (
            // **"₩0"이 아니라 "평가 전"이다.** 0을 쓰면 전액 손실로 읽힌다
            <div className="text-[12px] text-fg-faint">평가 전</div>
          )}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 border-t border-line-card pt-3 text-[11px] sm:grid-cols-4">
        <Cell label="취득일" value={asset.acquiredAt} />
        <Cell label="취득가" value={won(asset.acquiredCostKrw)} mono />
        <Cell
          label="적용 시세"
          // 단위를 서버가 준 값 그대로 붙인다 — 'KRW/g'를 코드에 박으면 소스가 단위를
          // 바꾼 날 화면만 조용히 틀린다(AF-108이 단위를 행에 저장하는 이유가 그것이다)
          value={
            asset.unitPrice !== null
              ? `${Number(asset.unitPrice).toLocaleString('en-US')} ${asset.priceUnit ?? ''}`.trim()
              : '—'
          }
          mono
        />
        <Cell label="기준일" value={priceAsOfLabel(asset.priceAsOf)} />
      </div>

      {/* 묵은 시세는 배지로 알린다. 임계치 5의 근거는 lib/real-asset-format.ts에 있다 —
          1~4는 정상 운영이라 여기 뜨면 안 된다 */}
      {stale && (
        <div className="mt-2">
          <Badge variant="warn">시세 {asset.stalenessDays}일 지연 — 소스 확인 필요</Badge>
        </div>
      )}
    </Panel>
  )
}

function Cell({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <Label size="sm" tone="faint">{label}</Label>
      <div className="mt-0.5 text-fg-2">{mono ? <Num>{value}</Num> : value}</div>
    </div>
  )
}
