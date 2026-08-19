package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.DartCorpMapEntity
import org.springframework.data.jpa.repository.JpaRepository

/** `corp_code` 단건 조회와 `save()` upsert 외에 별도 쿼리가 필요 없다(주 1회 전건 갱신) */
interface DartCorpMapJpaRepository : JpaRepository<DartCorpMapEntity, String>
