package com.allfolio.api.recon

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.reconciliation.application.ReconRunService
import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunStatus
import com.allfolio.reconciliation.domain.RunType
import com.allfolio.reconciliation.infrastructure.entity.ReconRunEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultDetailJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultSummaryJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconRunJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class ReconControllerSecurityTest {

    private val runRepository = mock(ReconRunJpaRepository::class.java)
    private val summaryRepository = mock(ReconResultSummaryJpaRepository::class.java)
    private val detailRepository = mock(ReconResultDetailJpaRepository::class.java)

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(
            ReconController(
                mock(ReconRunService::class.java),
                runRepository, summaryRepository, detailRepository,
            )
        )
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun run(userId: UUID) = ReconRunEntity(
        userId = userId, runDate = LocalDate.of(2026, 7, 31),
        runType = RunType.ALL, status = RunStatus.COMPLETED, triggerType = ReconTrigger.MANUAL,
    )

    @Test
    fun `내 run 조회는 200`() {
        val userId = UUID.randomUUID()
        val run = run(userId)
        `when`(runRepository.findById(run.id)).thenReturn(Optional.of(run))
        `when`(summaryRepository.findByRunId(run.id)).thenReturn(emptyList())

        mockMvc.get("/api/recon/runs/${run.id}") {
            header("X-User-Id", userId.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.run.status") { value("COMPLETED") }
        }
    }

    @Test
    fun `타인 run 조회는 404로 은닉한다`() {
        val run = run(UUID.randomUUID())
        `when`(runRepository.findById(run.id)).thenReturn(Optional.of(run))

        mockMvc.get("/api/recon/runs/${run.id}") {
            header("X-User-Id", UUID.randomUUID().toString())
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `없는 run 조회는 404`() {
        val id = UUID.randomUUID()
        `when`(runRepository.findById(id)).thenReturn(Optional.empty())

        mockMvc.get("/api/recon/runs/$id") {
            header("X-User-Id", UUID.randomUUID().toString())
        }.andExpect {
            status { isNotFound() }
        }
    }
}
