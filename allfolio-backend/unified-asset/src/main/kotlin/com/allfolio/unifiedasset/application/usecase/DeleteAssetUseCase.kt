package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 자산 1건 삭제 (AF-153).
 *
 * 예전에는 자산 하나를 지우려면 **계좌째 삭제**하는 수밖에 없었고, 그러면 그 계좌의 다른
 * 자산과 거래내역까지 함께 날아갔다. R2 단지·평형 선택이 들어오면서 잘못 등록할 경로가
 * 늘어난 터라(`symbol`·`exclusive_area_m2`가 틀리면 자동 평가가 틀린 값을 계속 순자산에
 * 올린다) 개별 삭제가 필요해졌다.
 *
 * **과거 NAV 스냅샷은 정정하지 않는다.** 오늘 행만 다시 쓴다. 과거 행의 값은 그날 자산이
 * 실제로 어떤 값이었는지가 있어야 다시 계산되는데 자산에는 값의 이력이 없다 —
 * `currentValue`는 오늘 값 하나뿐이라 그걸 과거에서 빼면 **근거 없는 숫자를 만들어 넣는
 * 것**이 된다. 등록 경로(`AccountController.addManualAsset`)도 같은 이유로 오늘만 쓴다.
 */
@Service
class DeleteAssetUseCase(
    private val assetRepository: AssetRepository,
    private val snapshotService: PerformanceSnapshotService,
) {
    /** 테스트에서 KST 경계를 고정하기 위해 노출한다 (`FscStockClient`와 같은 방식). */
    internal var clock: Clock = Clock.system(KST)

    @Transactional
    fun execute(userId: UUID, accountId: UUID, assetId: UUID) {
        val asset = assetRepository.findById(assetId)
            ?: throw NoSuchElementException("Asset not found: $assetId")

        // 소유권·소속을 둘 다 본다. 계좌 소유권만 확인하면 남의 계좌 자산 id를 내 계좌
        // 경로에 실어 지울 수 있다 — QA에서 이체·환전으로 겪었던 IDOR과 같은 모양이다.
        if (asset.userId != userId || asset.accountId != accountId) {
            throw NoSuchElementException("Asset not found: $assetId")
        }

        require(asset.sourceType in DELETABLE_SOURCES) {
            "동기화로 받아 온 자산은 삭제할 수 없습니다 — 다음 동기화에서 다시 생성됩니다"
        }

        assetRepository.delete(assetId)

        // 등록·CSV 임포트와 같은 자리에서 같은 일을 한다 — 그래야 삭제 직후 대시보드가
        // 지워진 자산을 뺀 순자산을 보인다. 통화별 내역까지 남겨야 AF-106 기여도 분해가
        // 이 날짜를 건너뛰지 않는다.
        snapshotService.record(
            userId,
            assetRepository.findByUserId(userId).navByCurrency(),
            LocalDate.now(clock.withZone(KST)),
        )
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 사람이 넣은 자산만 지운다. 동기화 계좌는 `deleteByAccountId` 후 `saveAll`로
         * 통째로 교체되므로(`SyncAccountUseCase`) 지워도 다음 동기화가 되살린다.
         * CSV 계좌는 `CsvSyncAdapter`가 기존 자산을 그대로 돌려주므로 삭제가 유지된다.
         */
        private val DELETABLE_SOURCES = setOf(AssetSourceType.MANUAL, AssetSourceType.CSV)
    }
}
