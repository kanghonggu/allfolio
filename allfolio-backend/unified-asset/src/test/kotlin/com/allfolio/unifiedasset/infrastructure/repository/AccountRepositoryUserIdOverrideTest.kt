package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.infrastructure.jpa.AccountJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * **실물 어댑터가 복호화를 안 타고 소유자를 찾는지** 고정한다 (AF-193).
 *
 * ## 왜 이런 테스트가 필요한가
 *
 * 포트의 `findUserId`에는 `findById`를 타는 **기본 구현**이 있다. 그런데 이 메서드가 필요한
 * 순간은 정확히 **`findById`가 복호화하다 터진 때**다 — 기본 구현을 그대로 쓰면 그때 null을
 * 주고, 실패 로그는 조용히 안 남는다. AF-193이 고친 바로 그 증상으로 되돌아간다.
 *
 * 기본 구현이 있으니 **재정의를 지워도 컴파일은 된다.** 그래서 테스트로 붙잡는다.
 *
 * ## 🔴 처음엔 반사(reflection)로 짰다가 헛돌았다
 *
 * `declaredMethods`에 `findUserId`가 있는지 봤는데, **재정의를 지워도 통과했다.** 코틀린이
 * 인터페이스 기본 구현을 구현 클래스에 전달 메서드로 생성하기 때문이다. 검사가 참·거짓
 * 어느 쪽이든 통과하고 있었다 — 변이를 넣어 보고서야 알았다.
 *
 * 그래서 **행동으로 검사한다**: `findById`가 터지는 상황에서 `findUserId`가 답하는지.
 */
class AccountRepositoryUserIdOverrideTest {

    @Test
    fun `findById가 복호화하다 터져도 소유자를 찾는다`() {
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val jpa = mock(AccountJpaRepository::class.java)
        `when`(jpa.findById(id)).thenThrow(RuntimeException("복호화 실패"))
        `when`(jpa.findUserIdById(id)).thenReturn(owner)

        val repo: AccountRepository = AccountRepositoryImpl(jpa)

        assertThat(repo.findUserId(id))
            .describedAs(
                "복호화를 타지 않는 경로로 소유자를 읽어야 한다. 포트의 기본 구현(findById 경유)에 " +
                    "기대면 여기서 null이 되고, 계좌 조회 실패가 ua_sync_logs에 안 남는다(AF-193).",
            )
            .isEqualTo(owner)
    }

    @Test
    fun `계좌 행이 없으면 null이다`() {
        val id = UUID.randomUUID()
        val jpa = mock(AccountJpaRepository::class.java)
        `when`(jpa.findUserIdById(id)).thenReturn(null)

        assertThat(AccountRepositoryImpl(jpa).findUserId(id)).isNull()
    }
}
