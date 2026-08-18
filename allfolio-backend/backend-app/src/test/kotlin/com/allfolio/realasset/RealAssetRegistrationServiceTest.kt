package com.allfolio.realasset

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RealAssetRegistrationServiceTest {

    private val user = UUID.randomUUID()
    private val today = LocalDate.of(2026, 8, 18)
    private val now = Instant.parse("2026-08-18T10:30:00Z")

    /**
     * **`sourceRef`를 요청에서 받지 않는다 — 이게 이 서비스의 핵심 방어다.**
     *
     * 받으면 사용자가 `WTI`나 `COPPER`를 넣어 원유 배럴값을 금 g수에 곱한 평가액을 만들 수 있다.
     * 오류도 안 나고 로그도 안 남는다 — 평가 어댑터는 그저 시키는 코드를 조회할 뿐이다.
     * 단위 가드(`KRW/g`)가 대부분을 걸러 주지만 그건 우연이지 설계가 아니다.
     * v1은 금만 받으므로 코드는 **하나로 정해져 있다.** 서버가 넣는다.
     */
    @Test
    fun `시세 조인 키는 서버가 정한다`() {
        val store = FakeStore()

        service(store).register(user, request(), today, now)

        assertThat(store.saved.single().sourceRef).isEqualTo("GOLD_KRX")
    }

    /**
     * TWR 포함 여부도 사용자가 정할 것이 아니다. 계단식 평가 자산이 금융자산 TWR에 섞이면
     * 수익률 자체가 오염된다 — 금은 연속(영업일)이라 포함이고, 그 판단은 자산 유형이 진다.
     */
    @Test
    fun `TWR 포함 여부는 자산 유형이 정한다`() {
        val store = FakeStore()

        service(store).register(user, request(), today, now)

        assertThat(store.saved.single().includeInTwr).isTrue()
    }

    /** 소유자는 헤더에서 온 사용자다. 요청 본문에 사용자를 실을 자리 자체를 안 만든다 */
    @Test
    fun `소유자는 인증된 사용자다`() {
        val store = FakeStore()

        service(store).register(user, request(), today, now)

        assertThat(store.saved.single().userId).isEqualTo(user)
    }

    /** v1은 금만이다. 시계·부동산을 받아 두면 평가 어댑터가 없어 영원히 안 잡히는 자산이 쌓인다 */
    @Test
    fun `금이 아닌 유형은 400이다`() {
        assertThatThrownBy {
            service(FakeStore()).register(user, request(assetType = AssetType.WATCH), today, now)
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("금")
    }

    @Test
    fun `수량이 0 이하면 400이다`() {
        assertThatThrownBy { service(FakeStore()).register(user, request(quantity = "0"), today, now) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("수량")
    }

    /**
     * 순도는 0 초과 1 이하다. 1을 넘으면 24K보다 순수한 금이 되어 평가액이 부풀고,
     * 0 이하면 평가액이 0이나 음수가 된다.
     */
    @Test
    fun `순도가 1을 넘으면 400이다`() {
        assertThatThrownBy { service(FakeStore()).register(user, request(purity = "1.5"), today, now) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("순도")
    }

    /**
     * 미래 취득일은 막는다. 취득일이 오늘보다 뒤면 그 자산은 존재하지 않는 기간의 손익을 갖게 된다.
     * **KST 오늘을 호출자가 넘긴다** — 서비스가 `LocalDate.now()`를 부르면 컨테이너 UTC라
     * 한국 사용자가 오늘 산 금을 저녁에 등록할 때 "미래"로 걸린다.
     */
    @Test
    fun `취득일이 미래면 400이다`() {
        assertThatThrownBy {
            service(FakeStore()).register(user, request(acquiredAt = today.plusDays(1)), today, now)
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("취득일")
    }

    /** 오늘 산 금은 등록할 수 있어야 한다 — 경계가 닫혀 있는지 본다 */
    @Test
    fun `취득일이 오늘이면 등록된다`() {
        val store = FakeStore()

        service(store).register(user, request(acquiredAt = today), today, now)

        assertThat(store.saved).hasSize(1)
    }

    /** v1은 24K 고정이라 순도를 안 주는 것이 기본 경로다 */
    @Test
    fun `순도를 안 주면 1이다`() {
        val store = FakeStore()

        service(store).register(user, request(purity = null), today, now)

        assertThat(store.saved.single().purity).isEqualByComparingTo("1.0")
    }

    /** 등록 즉시 활성이다 — 비활성으로 들어가면 배치가 영원히 건너뛴다 */
    @Test
    fun `등록한 자산은 활성이다`() {
        val store = FakeStore()

        service(store).register(user, request(), today, now)

        assertThat(store.saved.single().isActive).isTrue()
    }

    // ── 테스트 픽스처 ───────────────────────────────────────────────────────

    private fun service(store: FakeStore) = RealAssetRegistrationService(store)

    private fun request(
        assetType: AssetType = AssetType.GOLD,
        quantity: String = "3.75",
        purity: String? = "1.0",
        acquiredAt: LocalDate = LocalDate.of(2026, 8, 14),
    ) = RealAssetRegistration(
        assetType = assetType,
        subType = "KRX_ACCOUNT",
        name = "금 1돈",
        quantity = BigDecimal(quantity),
        purity = purity?.let { BigDecimal(it) },
        acquiredAt = acquiredAt,
        acquiredCostKrw = 750_000L,
    )

    private class FakeStore : RealAssetRegistrationService.Store {
        val saved = mutableListOf<NewRealAsset>()

        override fun insert(asset: NewRealAsset) {
            saved += asset
        }
    }
}
