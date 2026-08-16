// components/market/CommodityPanel.tsx
'use client'

import type { CommodityQuoteView } from '@/types/market'
import { commodityLabel, commoditySection } from '@/lib/market-labels'
import Label from '@/components/ui/Label'
import Num from '@/components/ui/Num'
import { EmptyState } from '@/components/ui/states'
import { dirTone } from '@/lib/format'
import { fixed } from '@/lib/market-format'

/**
 * 원자재 시세 (AF-108).
 *
 * **호출부가 null을 걸러서 넘긴다.** `null`은 플래그 off라 탭 자체가 없다는 뜻이고,
 * `[]`는 켜져 있는데 데이터가 없다는 뜻이다 — 여기서 `?? []`로 합치면 킬 스위치가 화면상
 * 무력해진다(지수 카드가 같은 계약이다).
 *
 * **두 섹션으로 가르는 이유는 신선도가 층마다 다르기 때문이다.** 일간은 사흘, 월간은 두 달
 * 묵는다. 한 표에 놓으면 사용자가 두 층을 같은 나이로 읽는다.
 * 가르는 근거는 소스 이름이 아니라 `frequency`다 — 금(FSC)이 붙어도 이 파일은 안 바뀐다.
 */

/**
 * 가격은 소수 2자리로 고정한다. `JSON.parse`가 뒤 0을 버려 그냥 두면 `2.79 / 17588.2855 / 68.5`
 * 처럼 열이 어긋나기 때문이다. 값 범위가 2.79(천연가스)~17,588.29(니켈)로 넓지만 `fixed()`는
 * 자리수와 무관하게 동작한다. 등락률 2자리는 환율·금리 탭과 같은 관례다.
 *
 * **여기서 자르는 것이 있다 — "손실이 없다"고 읽지 말 것.** 일간(EIA) 계열만 원본이 2자리이고,
 * 월간 IMF 13종은 소수가 길다(구리 13552.04090909…). 수집기가 `price`에 `setScale`을 걸지
 * 않아 `NUMERIC(18,4)` 컬럼이 4자리로 반올림해 저장하고 API가 그대로 내보내므로,
 * 화면은 그 4자리를 2자리로 자른다. 이 크기대의 넷째 자리는 잡음이라 자르는 쪽을 골랐다 —
 * 13,552에서 0.0009는 의미가 없고, 넓은 범위를 한 표에서 세로로 훑는 데는 2자리가 낫다.
 * 4자리로 올릴 일이 생기면 이 전제부터 다시 볼 것.
 */
const PRICE_DIGITS = 2
const CHANGE_DIGITS = 2
const RATE_DIGITS = 2

/**
 * 월간 관측의 기준일은 그 달 1일이다 — **그 날 하루 값이 아니라 그 달의 평균이다.**
 * `2026-06-01`을 그대로 찍으면 "6월 1일의 시세"로 읽힌다.
 *
 * `new Date()`로 파싱하지 않는다 — `'2026-06-01'`은 UTC 자정으로 해석돼 UTC 뒤쪽 존에서는
 * 5월로 밀린다. 문자열을 자르는 쪽이 존에 안 흔들린다(lib/date.ts가 같은 함정을 적어 뒀다).
 */
function monthLabel(tradeDate: string): string {
  const parts = tradeDate.split('-')
  const year = parts[0]
  const month = parts[1]
  if (!year || !month) return tradeDate
  return `${year}년 ${Number(month)}월`
}

function Section({
  title,
  note,
  rows,
  changeHeader,
  monthly,
}: {
  title: string
  note?: string
  rows: CommodityQuoteView[]
  changeHeader: string
  /** 기준일을 "2026년 6월"로 그린다. 일간은 날짜 그대로 */
  monthly?: boolean
}) {
  // 빈 단은 아예 안 그린다 — 주기가 D·M뿐이면 '기타'가 사라지는 게 정상이다
  if (rows.length === 0) return null
  return (
    <div>
      <Label size="sm" tone="faint">{title}</Label>
      {note && <p className="mt-1 text-[11px] text-fg-faint">{note}</p>}
      {/* 390px에서도 표가 페이지를 밀지 않게 이 안에서 가로 스크롤한다 — 환율·금리 탭과 같다 */}
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="border-b border-line-card text-left text-fg-faint">
              <th className="whitespace-nowrap py-1 font-normal">종목</th>
              <th className="py-1 pl-3 text-right font-normal">가격</th>
              <th className="py-1 pl-3 text-right font-normal">{changeHeader}</th>
              <th className="py-1 pl-3 text-right font-normal">기준</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.code} className="border-b border-line-card/50">
                {/* 라벨이 없으면 코드가 그대로 나온다 — 빈칸이면 종목이 사라진 것처럼 보인다.
                    390px에서 열이 눌리면 "원자재 종합지수"가 한 줄에 한 글자씩 여섯 줄로 쪼개진다 —
                    줄바꿈을 막고 표를 가로로 흘린다 */}
                <td className="whitespace-nowrap py-1.5">{commodityLabel(c.code)}</td>
                {/* 값과 단위를 한 덩어리로 묶어 줄바꿈을 막는다 — 390px에서 단위만 아랫줄로
                    떨어지면 위 행의 값과 붙어 보여 어느 단위가 어느 값인지 헷갈린다.
                    대신 표가 가로 스크롤로 들어간다(위 overflow-x-auto) */}
                <td className="whitespace-nowrap py-1.5 pl-3 text-right">
                  <Num className="text-[13px]">{fixed(c.price, PRICE_DIGITS)}</Num>{' '}
                  {/* **단위를 숨기지 말 것.** `USD/lb`(우라늄)와 `USc/lb`(설탕·커피)는 한 글자
                      차이에 100배 차이라, 없으면 커피 307.83이 달러로 읽힌다.
                      행에 실려 온 값을 쓴다 — 코드로 매핑한 상수를 두면 설정이 바뀐 날 조용히 틀린다 */}
                  <span className="font-mono text-[10px] text-fg-faint">{c.unit}</span>
                </td>
                <td className="py-1.5 pl-3 text-right">
                  {/* **`c.changeValue &&`로 가르면 안 된다** — number라 0이 falsy여서
                      "안 움직였다"(0.00)가 "직전 값 없음"(대시)으로 둔갑한다. AF-104가 그 사고를 냈다 */}
                  {c.changeValue != null ? (
                    <Num tone={dirTone(c.changeValue)}>
                      {fixed(c.changeValue, CHANGE_DIGITS)}
                      {/* 변화폭은 있는데 변화율만 null인 경우가 있다 — 직전 값이 0이면
                          나눌 수 없다. 그때는 괄호째 뺀다(대시 괄호는 열만 어지럽힌다) */}
                      {c.changeRate != null && ` (${fixed(c.changeRate, RATE_DIGITS)}%)`}
                    </Num>
                  ) : (
                    <span className="text-fg-faint">-</span>
                  )}
                </td>
                {/* **기준일을 항목마다 단다.** 같은 탭 안에서 기준일이 두 달까지 벌어진다 —
                    공통 헤더에 하나를 두면 화면이 거짓말을 한다(금리 탭이 같은 이유로 그렇게 한다) */}
                {/* 날짜는 안 쪼갠다 — 390px에서 `2026-08-` / `13`으로 갈리면 두 줄짜리 행이
                    되고 기준일이 다른 행의 것으로 읽힌다. 대신 표가 가로 스크롤로 들어간다 */}
                <td className="whitespace-nowrap py-1.5 pl-3 text-right font-mono text-[10px] text-fg-faint">
                  {monthly ? monthLabel(c.tradeDate) : c.tradeDate}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default function CommodityPanel({ quotes }: { quotes: CommodityQuoteView[] }) {
  if (quotes.length === 0) return <EmptyState title="원자재 데이터가 아직 없습니다" />

  // 백엔드가 설정 순서(일간 → 월간)로 주지만 순서에 기대지 않고 주기로 다시 가른다 —
  // 순서에 기대면 설정이 재배열된 날 월간 값이 「시세」 섹션에 섞인다.
  const daily = quotes.filter((c) => commoditySection(c.frequency) === 'DAILY')
  const monthly = quotes.filter((c) => commoditySection(c.frequency) === 'MONTHLY')
  // D도 M도 아닌 주기가 오면 여기로 온다. 버리지 않는 이유: 값이 틀린 것보다
  // 종목이 조용히 사라진 것이 알아채기 더 어렵다
  const etc = quotes.filter((c) => commoditySection(c.frequency) === 'ETC')

  return (
    <div className="space-y-6">
      <Section title="시세" rows={daily} changeHeader="전일대비" />
      <Section
        title="월간 지표"
        note="국제기구 월평균이라 두 달가량 늦습니다. 기준은 해당 월 전체의 평균값입니다."
        rows={monthly}
        changeHeader="전월대비"
        monthly
      />
      <Section title="기타" rows={etc} changeHeader="직전대비" />

      {/* **각주 두 줄 — 없애지 말 것(설계 §6).** 빼면 "왜 은이 없지"·"왜 금값이 다르지"가
          남고 다음 사람이 같은 조사를 다시 한다 */}
      <div className="space-y-1 border-t border-line-card pt-3 text-[10px] leading-relaxed text-fg-faint">
        <p>은·백금은 국제 시세 재배포 라이선스 때문에 싣지 않습니다.</p>
        <p>금은 KRX 금시장 원/g 기준이라 국제 금값(USD/oz)과 다릅니다.</p>
      </div>
    </div>
  )
}
