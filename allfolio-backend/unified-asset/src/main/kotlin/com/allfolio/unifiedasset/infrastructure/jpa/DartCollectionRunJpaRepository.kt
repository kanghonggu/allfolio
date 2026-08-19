package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.DartCollectionRunEntity
import org.springframework.data.jpa.repository.JpaRepository

/** 실행 기록 적재·갱신 전용. `save()`로 시작 행을 만들고 종료 시 같은 행을 다시 `save()`한다 */
interface DartCollectionRunJpaRepository : JpaRepository<DartCollectionRunEntity, Long>
