package com.allfolio.unifiedasset.ci

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * # 🚨 머지하지 말 것 — 일부러 실패하는 드릴 테스트 (AF-171)
 *
 * ## 왜 있나
 *
 * `deploy.yml`은 PR마다 `./gradlew test`를 돌리고 체크를 붙인다. 그런데 2026-09-08까지
 * **PR에서 실행된 23번이 전부 초록**이었다 — 즉 **빨간불이 뜨는 것을 아무도 본 적이 없다.**
 *
 * 초록만 본 관문은 "막는다"의 증거가 아니다. 워크플로가 실패를 삼키거나(`|| true`,
 * `continue-on-error`), 체크가 PR에 안 붙거나, 필수 체크로 안 잡혀 있어도 **초록은 똑같이
 * 초록으로 보인다.** 그래서 한 번은 실제로 빨갛게 만들어 봐야 한다.
 *
 * ## 어떻게 쓰나
 *
 * 이 파일이 든 브랜치로 PR을 열고 **`Backend tests` 체크가 빨개지는지**, 실패 리포트
 * 아티팩트가 올라오는지 확인한 뒤 **PR을 닫고 브랜치를 지운다.** 절대 머지하지 않는다.
 *
 * 관측 결과는 AF-171 티켓에 남긴다.
 */
class CiRedCheckDrillTest {

    @Test
    fun `이 테스트는 CI가 빨개지는지 보려고 일부러 실패한다`() {
        assertThat(1)
            .describedAs("AF-171 드릴 — 이 실패가 PR에 빨간 체크로 보여야 한다. 이 브랜치는 머지 대상이 아니다")
            .isEqualTo(2)
    }
}
