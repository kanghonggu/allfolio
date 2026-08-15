package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 사람이 손으로 넣은 보정 레코드까지 걸러내기 위한 메모 표식 (AF-93) */
private const val INITIAL_INFLOW_MARKER = "계좌 연동 초기 자산 편입"

data class SyncResult(
    val accountId: UUID,
    val synced: Int,
    val status: AccountStatus,
    val error: String? = null,
)

@Service
class SyncAccountUseCase(
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val adapters: List<SyncAdapter>,
    private val snapshotService: PerformanceSnapshotService,
    private val fx: FxConverter,
    private val syncLogRepository: SyncLogRepository,
    private val reconMutex: ReconMutex,
    private val cashFlowRepository: CashFlowRepository,
    private val stockTradeRepository: StockTradeRepository,
) : AccountSyncRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adapterMap: Map<AccountProvider, SyncAdapter> by lazy {
        adapters.associateBy { it.supportedProvider }
    }

    @Transactional
    override fun execute(accountId: UUID, trigger: SyncTrigger): SyncResult {
        val account = try {
            accountRepository.findById(accountId)
        } catch (e: RuntimeException) {
            if (e.requiresSensitiveDataReconnection()) {
                runCatching { accountRepository.updateStatus(accountId, AccountStatus.ERROR) }
                return SyncResult(accountId, 0, AccountStatus.ERROR, SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
            }
            throw e
        } ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "Account not found")

        val adapter = adapterMap[account.provider]
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "No adapter for ${account.provider}")
                .also { record(account, trigger, it) }

        // 대사↔동기화 상호 배제 (#17) — 대사 진행 중이면 계좌 상태는 건드리지 않고 건너뛴다
        val lockToken = reconMutex.tryAcquire(account.userId)
            ?: return SyncResult(accountId, 0, AccountStatus.ERROR, "대사가 진행 중이라 동기화를 건너뜁니다")
                .also { record(account, trigger, it) }

        accountRepository.updateStatus(accountId, AccountStatus.SYNCING)

        return try {
            val assets: List<Asset> = adapter.sync(account)
            // 계좌에 자산이 처음 들어오는 순간인지 — 교체(full refresh) 전에 판정
            val hadAssets = assetRepository.findByAccountId(accountId).isNotEmpty()
            // 기존 자산 삭제 후 새 자산으로 교체 (full refresh)
            assetRepository.deleteByAccountId(accountId)
            assetRepository.saveAll(assets)
            accountRepository.updateStatus(accountId, AccountStatus.ACTIVE)

            // QA P1 #8: 최초 편입 자산은 수익이 아니라 원금 유입 — external inflow로 기록해야
            // TWR/MWR이 초기 평가액을 수익률로 오인하지 않는다. 사용자 수동 입력에 의존하지 않는다.
            if (!hadAssets && assets.isNotEmpty()) {
                recordInitialInflow(account, assets)
            }

            // 이 계좌 유저의 전체 NAV를 스냅샷으로 기록 (통화 혼재 → KRW 환산 후 합산)
            val allAssets = assetRepository.findByUserId(account.userId)
            val nav = allAssets.navInKrw(fx)
            snapshotService.record(account.userId, nav, LocalDate.now(ZoneId.of("Asia/Seoul")))

            log.info("Synced ${assets.size} assets for account $accountId (${account.provider})")
            SyncResult(accountId, assets.size, AccountStatus.ACTIVE)
                .also { record(account, trigger, it) }
        } catch (e: Exception) {
            log.error("Sync failed for account $accountId: ${e.message}", e)
            accountRepository.updateStatus(accountId, AccountStatus.ERROR)
            val error = if (e.requiresSensitiveDataReconnection()) {
                SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
            } else {
                e.message
            }
            SyncResult(accountId, 0, AccountStatus.ERROR, error)
                .also { record(account, trigger, it) }
        } finally {
            reconMutex.release(account.userId, lockToken)
        }
    }

    /**
     * 최초 편입 자금을 external flow로 남긴다.
     *
     * 거래 로그가 있는 계좌는 체결일·체결금액으로 소급 생성한다 (AF-93). 예전에는 오늘
     * 날짜·현재 평가액으로 한 건만 남겨서, 1년 전 700만을 넣어 2,315만이 된 계좌가
     * "오늘 2,315만 입금"으로 기록됐다 — 과거 수익 1,615만이 영구히 사라지는 셈이었다.
     *
     * 투입 시점을 알아낼 수 없는 계좌 — 잔고 조회만 하는 API 연동, 그리고 거래 로그가
     * 있어도 소급 흐름이 한 건도 안 나오는 계좌(배당만 있는 경우) — 는 연동 시점·평가액.
     */
    private fun recordInitialInflow(account: Account, assets: List<Asset>) {
        if (hasInitialInflow(account)) return

        val trades = runCatching { stockTradeRepository.findByAccountId(account.id) }
            .getOrElse {
                log.warn("거래 로그 조회 실패 — 평가액 기준으로 기록 accountId={}", account.id, it)
                emptyList()
            }

        // 거래 로그가 있어도 소급 흐름이 안 나올 수 있다 — 배당만 있는 계좌가 그렇다.
        // 그때 빈 손으로 끝내면 다음 sync는 hadAssets=true라 여기까지 오지도 못하고,
        // 그 계좌는 외부 투입 0인 채로 남아 NAV 전체가 수익으로 잡힌다.
        val flows = backdatedFlows(account, trades)
            .ifEmpty { listOfNotNull(valuationFlow(account, assets)) }
        flows.forEach { cashFlowRepository.save(it) }
    }

    /**
     * 이미 초기 편입이 기록돼 있으면 건드리지 않는다.
     *
     * 계좌 단위 확인만으로는 부족하다 — 운영 계정에는 수익률 보정을 위해 사람이 직접
     * 넣은 "계좌 연동 초기 자산 편입(소급 보정)" 레코드가 있고, 이게 계좌에 매이지 않은
     * 경우 중복 생성되면 netFlow가 두 배로 잡혀 수익률이 다시 틀어진다.
     *
     * 메모 표식은 계좌 없는 레코드에만 적용한다. 다른 계좌에 달린 편입 기록까지 막으면
     * 두 번째 계좌는 초기 편입이 영원히 기록되지 않는다.
     */
    private fun hasInitialInflow(account: Account): Boolean =
        runCatching {
            cashFlowRepository.findByUserId(account.userId).any {
                it.accountId == account.id ||
                    (it.accountId == null && it.memo?.contains(INITIAL_INFLOW_MARKER) == true)
            }
        }.getOrElse { e ->
            // 확인할 수 없으면 만들지 않는다 — 중복 기록이 누락보다 되돌리기 어렵다
            log.error("기존 현금흐름 확인 실패 — 초기 편입 기록을 건너뛴다 accountId={}", account.id, e)
            true
        }

    /** 매수는 투입(DEPOSIT), 매도는 회수(WITHDRAWAL). 포지션 계산과 같은 거래 유형 분류를 쓴다. */
    private fun backdatedFlows(account: Account, trades: List<StockTrade>): List<CashFlow> =
        trades.mapNotNull { trade ->
            val (type, amount) = when (trade.tradeType) {
                StockTradeType.BUY, StockTradeType.CREDIT_BUY ->
                    FlowType.DEPOSIT to trade.totalAmount + trade.fee + trade.tax
                StockTradeType.SELL, StockTradeType.CREDIT_SELL ->
                    FlowType.WITHDRAWAL to trade.totalAmount - trade.fee - trade.tax
                // 배당은 수익이지 외부 투입이 아니고, 미수는 포지션을 만들지 않는다
                else -> return@mapNotNull null
            }
            if (amount <= BigDecimal.ZERO) return@mapNotNull null

            // 오늘이 아니라 체결일 환율 — 오늘 환율로 환산하면 과거 USD 거래의 원금이 틀어진다.
            // StockTrade에 통화 필드가 없어 계좌 통화를 거래의 통화로 본다. 국내 KRW와 해외 USD를
            // 한 계좌에 섞어 담았다면 이 가정이 깨지는데(이전 코드도 같은 가정), 그때는 틀린 값이
            // "일률적으로 틀린 오늘 환율"에서 "정밀하게 틀린 과거 환율"로 바뀔 뿐이다.
            val conversion = fx.toKrwOn(amount, account.currency, trade.tradedAt)
            if (conversion.estimated) {
                // 어댑터 WARN은 통화·날짜만 찍어 어느 계좌·어느 거래인지 알 수 없다.
                // 나중에 정정 대상 집합을 로그에서 추려낼 수 있도록 행 식별자를 여기서 남긴다.
                log.warn(
                    "체결일 환율 미보유 — 현재 환율로 근사 accountId={} tradeId={} tradedAt={} currency={}",
                    account.id, trade.id, trade.tradedAt, account.currency,
                )
            }
            val memo = buildString {
                append("거래 로그 기준 자동 기록(${trade.stockName})")
                // 시스템이 만드는 메모이므로 부정확함을 여기 남긴다
                if (conversion.estimated) append(" · 환율 추정치")
            }
            CashFlow.create(
                userId = account.userId,
                accountId = account.id,
                flowDate = trade.tradedAt,
                type = type,
                amount = amount,
                currency = account.currency,
                amountKrw = conversion.amountKrw,
                memo = memo,
            )
        }

    /** 투입 시점을 알 수 없는 계좌 — 연동 시점 평가액을 원금으로 본다. */
    private fun valuationFlow(account: Account, assets: List<Asset>): CashFlow? {
        val initialNavKrw = assets.navInKrw(fx)
        if (initialNavKrw <= BigDecimal.ZERO) return null
        return CashFlow.create(
            userId = account.userId,
            accountId = account.id,
            flowDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
            type = FlowType.DEPOSIT,
            amount = initialNavKrw,
            currency = "KRW",
            amountKrw = initialNavKrw,
            memo = "$INITIAL_INFLOW_MARKER(자동)",
        )
    }

    /** 동기화 결과를 이력으로 남긴다. 이력 저장 실패가 동기화 결과에 영향을 주지 않게 격리. */
    private fun record(account: Account, trigger: SyncTrigger, result: SyncResult) {
        runCatching {
            syncLogRepository.save(
                SyncLog.create(
                    accountId = account.id,
                    userId = account.userId,
                    trigger = trigger,
                    status = if (result.status == AccountStatus.ACTIVE) SyncLogStatus.SUCCESS else SyncLogStatus.ERROR,
                    syncedCount = result.synced,
                    errorMessage = result.error,
                )
            )
        }.onFailure { e -> log.warn("sync log save failed accountId={}", account.id, e) }
    }
}
