package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.application.usecase.AccountSyncStatus
import com.allfolio.unifiedasset.application.usecase.GetSyncStatusUseCase
import com.allfolio.unifiedasset.application.usecase.SyncLogView
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * **동기화 시각은 오프셋을 달고 나가야 한다.**
 *
 * 2026-08-21 15:55 KST에 동기화한 계좌 상세 헤더가 `오전 6:55:18`을 표시했다 — 정확히 9시간.
 * `lastSyncedAt`이 `LocalDateTime`이라 Jackson이 오프셋 없이 적고(`"2026-08-21T06:55:18"`),
 * 브라우저의 `new Date(...)`가 그것을 **읽는 쪽 로컬 시각**으로 해석했다. Render 컨테이너에
 * TZ 설정이 없어 저장된 벽시계는 UTC다.
 *
 * `generatedAt`에서 이미 같은 결론을 냈다 — [com.allfolio.unifiedasset.application.usecase.ReportGeneratedAtOffsetTest].
 * 고치는 것은 값이 아니라 **전선 위의 타입**이다. 프런트에 KST를 박으면 한국 사용자에게만
 * 맞고 다른 시간대 사용자는 반대 방향으로 틀린다(실측: `TZ=America/New_York`에서 같은
 * 문자열이 `8. 20. 오후 5:55`로 읽힌다). 오프셋을 실으면 읽는 쪽이 추측할 여지가 없다.
 *
 * **단언은 직렬화된 문자열을 본다** — 브라우저가 실제로 손에 쥐는 것이 그것이다.
 */
class SyncTimeOffsetTest {

    /**
     * 운영 웹 계층이 쓰는 매퍼를 그대로 재현한다. `application.yml`에 `spring.jackson` 설정이
     * 없으므로 운영은 Boot 기본값이고, `WRITE_DATES_AS_TIMESTAMPS` 비활성은 Boot의
     * `JacksonAutoConfiguration`이 넣는 것이라 여기서 명시해야 한다.
     */
    private val webMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    private fun <T> inZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun wire(value: Any, field: String): String =
        webMapper.readTree(webMapper.writeValueAsString(value)).get(field).asText()

    /**
     * 브라우저가 보는 문자열이 (1) 오프셋을 싣고 (2) 지금 이 순간을 가리키는지.
     * 오프셋이 없으면 [OffsetDateTime.parse]가 던진다 — 브라우저는 던지는 대신 조용히 추측한다.
     */
    private fun assertCarriesOffsetAndPointsAtNow(wireValue: String) {
        assertThat(wireValue)
            .describedAs("오프셋이 없으면 읽는 쪽이 자기 로컬 시각으로 추측한다 (UTC 저장 → 한국 사용자에게 9시간 어긋남)")
            .matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+(Z|[+-]\\d{2}:\\d{2})")
        assertThat(Duration.between(OffsetDateTime.parse(wireValue).toInstant(), Instant.now()).abs())
            .describedAs("가리키는 절대 시각은 지금이어야 한다")
            .isLessThan(Duration.ofMinutes(1))
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private class FakeAccountRepository(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = accounts.find { it.id == id }
        override fun findByUserId(userId: UUID): List<Account> = accounts.filter { it.userId == userId }
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class FakeSyncLogRepository(private val latest: Map<UUID, SyncLog>) : SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = latest
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private fun syncedAccountStatus(): AccountSyncStatus {
        val userId = UUID.randomUUID()
        val account = Account.create(
            userId = userId, provider = AccountProvider.KIS,
            accountType = AccountType.STOCK, accountName = "kis", currency = "KRW",
        ).completeSync()
        val log = SyncLog.create(account.id, userId, SyncTrigger.MANUAL, SyncLogStatus.SUCCESS, 3, null)
        return GetSyncStatusUseCase(
            FakeAccountRepository(listOf(account)),
            FakeSyncLogRepository(mapOf(account.id to log)),
        ).execute(userId).single()
    }

    // ── 단언 ─────────────────────────────────────────────────────

    @Test
    fun `동기화 시각을 싣는 응답 DTO 필드는 전부 OffsetDateTime이다`() {
        val fields = listOf(
            AccountResponse::class to "lastSyncedAt",
            AccountSyncStatus::class to "lastSyncedAt",
            SyncLogView::class to "createdAt",
        )

        val offending = fields.mapNotNull { (dto, name) ->
            val declared = dto.memberProperties.first { it.name == name }.returnType
            if (declared.classifier == OffsetDateTime::class) null else "${dto.simpleName}.$name: $declared"
        }

        assertThat(offending)
            .describedAs("오프셋 없는 타입은 Jackson이 오프셋 없이 적고, 브라우저가 읽는 쪽 로컬 시각으로 추측한다")
            .isEmpty()
    }

    @Test
    fun `UTC 컨테이너에서 동기화한 계좌의 lastSyncedAt이 전선에서 지금을 가리킨다`() {
        val status = inZone("UTC") { syncedAccountStatus() }
        assertCarriesOffsetAndPointsAtNow(wire(status, "lastSyncedAt"))
    }

    @Test
    fun `UTC 컨테이너에서 남긴 동기화 로그의 createdAt이 전선에서 지금을 가리킨다`() {
        val status = inZone("UTC") { syncedAccountStatus() }
        assertCarriesOffsetAndPointsAtNow(wire(status.lastLog!!, "createdAt"))
    }

    /**
     * 저장 값이 UTC라는 것은 읽는 쪽 변환(`atOffset(UTC)`)의 전제다. 지금은 컨테이너가
     * UTC라서 우연히 맞을 뿐이라 — `LocalDateTime.now()`는 호스트 타임존을 따른다 —
     * KST 데스크톱에서 돌리면 9시간 미래가 저장된다. 그 전제를 못 박는다.
     */
    /**
     * **밀림이 자정을 넘기면 시각이 아니라 날짜가 틀린다.** 자정 배치가 KST 00:37에 돌면
     * UTC 벽시계로는 전날 15:37이 저장된다. 오프셋 없이 내보내면 한국 사용자는 "오늘 새벽"
     * 동기화를 **"어제 오후"**로 본다 — 2026-08-21 운영 관측에서 바이낸스·한투가 실제로
     * `8. 20. 오후 3:37`로 떠 있었다. 수동 Sync만 보면 시각만 맞추고 이 케이스를 놓친다.
     *
     * 저장 값을 고정해 실제 매핑에 통과시킨다 — `now()`를 쓰면 실행 시각에 따라 경계를
     * 안 넘어 통과해버린다.
     */
    @Test
    fun `자정 배치로 동기화된 계좌는 KST 달력으로 다음날이다 — 날짜가 하루 틀리던 케이스`() {
        val midnightBatchUtc = LocalDateTime.of(2026, 8, 20, 15, 37)   // KST 2026-08-21 00:37
        val userId = UUID.randomUUID()
        val account = Account.reconstruct(
            id = UUID.randomUUID(), userId = userId, provider = AccountProvider.BINANCE,
            accountType = AccountType.EXCHANGE, accountName = "binance", externalId = null,
            currency = "USDT", status = AccountStatus.ACTIVE,
            lastSyncedAt = midnightBatchUtc, createdAt = midnightBatchUtc,
            apiKey = null, apiSecret = null, walletAddress = null, chain = null,
        )

        val status = GetSyncStatusUseCase(
            FakeAccountRepository(listOf(account)), FakeSyncLogRepository(emptyMap()),
        ).execute(userId).single()

        val asKoreanUserSeesIt = OffsetDateTime.parse(wire(status, "lastSyncedAt"))
            .atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate()

        assertThat(asKoreanUserSeesIt)
            .describedAs("오프셋이 없으면 브라우저가 15:37을 KST 벽시계로 읽어 8/20 오후로 표시한다")
            .isEqualTo(LocalDate.of(2026, 8, 21))
    }

    @Test
    fun `저장 시각은 호스트 타임존과 무관하게 UTC다`() {
        val storedInKstDesktop = inZone("Asia/Seoul") {
            Account.create(
                userId = UUID.randomUUID(), provider = AccountProvider.KIS,
                accountType = AccountType.STOCK, accountName = "kis", currency = "KRW",
            ).completeSync().lastSyncedAt!!
        }

        // **`toHours()`로 단언하지 않는다** — 두 now() 사이 마이크로초 차이로 내림되면 어긋난다.
        assertThat(Duration.between(storedInKstDesktop, LocalDateTime.now(ZoneOffset.UTC)).abs().toMinutes())
            .describedAs("KST 벽시계를 저장하면 UTC보다 9시간(540분) 앞선다")
            .isLessThan(1L)
    }

    @Test
    fun `동기화 로그 저장 시각도 호스트 타임존과 무관하게 UTC다`() {
        val storedInKstDesktop = inZone("Asia/Seoul") {
            SyncLog.create(UUID.randomUUID(), UUID.randomUUID(), SyncTrigger.MANUAL, SyncLogStatus.SUCCESS, 3, null)
                .createdAt
        }

        assertThat(Duration.between(storedInKstDesktop, LocalDateTime.now(ZoneOffset.UTC)).abs().toMinutes())
            .isLessThan(1L)
    }
}
