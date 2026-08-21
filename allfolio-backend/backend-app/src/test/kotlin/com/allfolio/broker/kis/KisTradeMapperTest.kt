package com.allfolio.broker.kis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID

/**
 * `executedAt`은 **KIS가 준 한국 거래소 벽시계**다 — 다른 어댑터(토스·삼성)도 같은 규약으로
 * 브로커가 준 날짜+시각 문자열을 파싱한다.
 *
 * 파싱 실패 시 `LocalDateTime.now()`로 떨어지던 폴백을 없앤다. 그건 **호스트 벽시계**(운영 컨테이너는
 * UTC)라 같은 컬럼에 두 가지 존 의미가 섞였다. 그리고 `RecordTradeUseCase`가
 * `tradeDate = executedAt.toLocalDate()`로 날짜를 잘라 쓰기 때문에, 폴백이 걸린 거래는 **날짜가 하루
 * 어긋날 수 있다.** 그 날짜는 일별 스냅샷과 포지션 엔진의 입력이다.
 *
 * 언제인지 모르는 거래에 **지금 시각을 지어내 붙이지 않는다.** 이 함수는 이미 수량·가격·매매구분을
 * 못 읽으면 `null`로 건너뛴다 — 주문일시도 같게 다룬다. 건너뛴 항목은 다음 동기화에서 다시 시도되고,
 * 그래도 못 읽으면 계속 빠진다. 빠진 포지션은 대사(reconciliation)가 수량 불일치로 잡아내지만,
 * 지어낸 날짜는 아무도 못 잡는다.
 */
class KisTradeMapperTest {

    private val portfolioId: UUID = UUID.randomUUID()

    private fun item(orderDate: String, orderTime: String) = KisOrderItem(
        orderDate = orderDate,
        orderNo   = "0000123456",
        stockCode = "005930",
        stockName = "삼성전자",
        sideCode  = "02",
        filledQty = "10",
        avgPrice  = "70000",
        orderTime = orderTime,
    )

    private fun map(orderDate: String, orderTime: String) =
        KisTradeMapper.toCommand(item(orderDate, orderTime), portfolioId, portfolioId)

    private fun <T> inTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `정상 주문일시는 KIS가 준 벽시계 그대로다`() {
        assertThat(map("20260707", "141720")?.executedAt)
            .isEqualTo(LocalDateTime.of(2026, 7, 7, 14, 17, 20))
    }

    @Test
    fun `주문시각이 짧으면 0으로 채운다`() {
        // 기존 동작 보존 — 초가 생략돼 오는 경우를 padEnd로 메운다
        assertThat(map("20260707", "0930")?.executedAt)
            .isEqualTo(LocalDateTime.of(2026, 7, 7, 9, 30, 0))
    }

    @Test
    fun `주문일자를 못 읽으면 거래를 만들지 않는다`() {
        assertThat(map("", "141720"))
            .describedAs("언제인지 모르는 거래에 지금 시각을 지어내 붙이면 tradeDate가 하루 어긋난다")
            .isNull()
    }

    @Test
    fun `주문일시가 형식에 안 맞아도 거래를 만들지 않는다`() {
        assertThat(map("2026-07-07", "141720")).isNull()
    }

    @Test
    fun `결과는 호스트 타임존에 흔들리지 않는다`() {
        // 폴백이 남아 있으면 파싱 불가 항목의 결과가 호스트 시계를 타서 존마다 달라진다.
        val inSeoul = inTimeZone("Asia/Seoul") { map("", "141720") }
        val inUtc   = inTimeZone("UTC") { map("", "141720") }

        assertThat(inSeoul)
            .describedAs("호스트 벽시계가 결과에 새어 들어오면 안 된다")
            .isEqualTo(inUtc)
    }
}
