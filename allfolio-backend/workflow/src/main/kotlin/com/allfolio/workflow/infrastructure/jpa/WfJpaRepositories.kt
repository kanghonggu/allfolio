package com.allfolio.workflow.infrastructure.jpa

import com.allfolio.workflow.infrastructure.entity.WfDefHistEntity
import com.allfolio.workflow.infrastructure.entity.WfHolidayEntity
import com.allfolio.workflow.infrastructure.entity.WfHolidayId
import com.allfolio.workflow.infrastructure.entity.WfJobLogEntity
import com.allfolio.workflow.infrastructure.entity.WfStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepId
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface WfStepJpaRepository : JpaRepository<WfStepEntity, String> {
    fun findByUseYnTrueOrderByStepSeq(): List<WfStepEntity>
}

interface WfSubStepJpaRepository : JpaRepository<WfSubStepEntity, WfSubStepId> {
    fun findByIdStepCdAndUseYnTrueOrderBySubStepSeq(stepCd: String): List<WfSubStepEntity>
    fun findByIdStepCd(stepCd: String): List<WfSubStepEntity>
}

interface WfJobLogJpaRepository : JpaRepository<WfJobLogEntity, java.util.UUID> {
    fun findByYmd(ymd: LocalDate): List<WfJobLogEntity>
    fun findByYmdBetween(from: LocalDate, to: LocalDate): List<WfJobLogEntity>
    fun findByYmdAndStepCdAndSubStepCdOrderByExecSeqDesc(
        ymd: LocalDate, stepCd: String, subStepCd: String,
    ): List<WfJobLogEntity>
    fun findByYmdAndExecSeqGreaterThanOrderByStartedAtDesc(ymd: LocalDate, execSeq: Int): List<WfJobLogEntity>
}

interface WfDefHistJpaRepository : JpaRepository<WfDefHistEntity, java.util.UUID> {
    fun findAllByOrderByChangedAtDesc(): List<WfDefHistEntity>
}

interface WfHolidayJpaRepository : JpaRepository<WfHolidayEntity, WfHolidayId> {
    fun findByIdDayBetween(from: LocalDate, to: LocalDate): List<WfHolidayEntity>
}
