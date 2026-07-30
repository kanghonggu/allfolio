# R-07 배제리스트 관리 (SCR-RPT-11, Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자가 자기 배제리스트(리스트+종목)를 CRUD·CSV 반입·활성 토글·프리셋 복제로 관리하고, ESG 스크리닝이 그 리스트를 반영하게 한다(SCR-RPT-11, Phase A, USER-scoped).

**Architecture:** `unified-asset` 모듈에 헥사고날로 `ua_exclusion_lists`/`ua_exclusion_items` 도메인·엔티티·JPA·포트·서비스를 두고, `unified-asset/api`에 USER 컨트롤러(`/api/exclusion-lists`, `X-User-Id` 소유권, GoalController 패턴)를 둔다. `EsgScreeningReportGenerator`는 내장 프리셋 ∪ 유저 active 리스트로 스크리닝하도록 확장. FE는 `esg-screening/lists` 화면 + 진입 링크.

**Tech Stack:** Kotlin, Spring Boot 6, Spring Data JPA, JUnit5, PostgreSQL(init.sql), Next.js(App Router)/React/TS/axios.

**Spec:** `docs/superpowers/specs/2026-07-30-exclusion-list-management-design.md`
**Branch:** `feat/exclusion-list-management` (main에서 분기; ADMIN 무관)

---

## Reference: 현재 상태 & 관례

- 배제 판정은 하드코딩 `unified-asset/.../application/usecase/EsgExclusionPreset.kt`: `object EsgExclusionPreset { val entries: Map<String, ExclusionEntry(listName, reason)> = {"EXCL-COAL-01"→("예시 프리셋","석탄"), "EXCL-WEAPON-01"→("예시 프리셋","논란무기")}; fun lookup(symbol) }`.
- `EsgScreeningReportGenerator(assetRepository, fx)` (@Component)가 `EsgExclusionPreset.lookup(a.symbol)`로 violations 생성. 본문: `violations[{name,symbol,listName,reason,valueKrw,weight}]`, `screening{violationCount,violationValueKrw,violationWeight}`.
- USER CRUD 관례: `unified-asset/api/GoalController.kt`(`@RequestMapping("/api/goals")`, 각 메서드 `@RequestHeader("X-User-Id") userId: UUID`, `@PostMapping @ResponseStatus(CREATED)`, `@DeleteMapping("/{id}") @ResponseStatus(NO_CONTENT)`). 소유권: 서비스에서 `findById` 후 소유자 불일치 거부(GoalService는 `error()/require()`; **본 신규 코드는 `ResponseStatusException(NOT_FOUND/FORBIDDEN)` 사용**).
- 엔티티 관례(`infrastructure/entity/GoalEntity.kt`): `@Table("ua_goals")`, `@Id @Column(columnDefinition="uuid")`, `@Column(name="user_id", columnDefinition="uuid")`, `toDomain()`+`companion fromDomain()`. JPA: `interface : JpaRepository<Entity, UUID>` 파생쿼리. 어댑터: `@Repository class XImpl(jpa) : Port`.
- init.sql `ua_goals`(line ~211): PK만, `CREATE INDEX ... ON ua_goals(user_id)`. FK cascade 예: `REFERENCES ua_accounts(id) ON DELETE CASCADE`.
- ESG 생성기 테스트(`EsgScreeningReportGeneratorTest`): `private fun generator(assets) = EsgScreeningReportGenerator(FakeAssetRepo(assets), fx)`; `standardAssets()`에 `asset("석탄기업","EXCL-COAL-01","2000000")` 포함. FakeAssetRepo/fx fake 존재.
- FE: `app/unified/reports/esg-screening/page.tsx`(ESG 스크리닝 목록/생성). API 클라이언트 관례 `lib/report-archive-api.ts`(axios+Bearer, `${NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}`). `@/*`→`./*`.

**공통 규칙:** 경로 = 리포 루트 기준. BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset (신규)**
- `domain/exclusion/ExclusionList.kt`, `domain/exclusion/ExclusionItem.kt`
- `infrastructure/entity/ExclusionListEntity.kt`, `ExclusionItemEntity.kt`
- `infrastructure/jpa/ExclusionListJpaRepository.kt`, `ExclusionItemJpaRepository.kt`
- `application/port/ExclusionListRepository.kt`
- `infrastructure/repository/ExclusionListRepositoryImpl.kt`
- `application/usecase/ExclusionListService.kt` (+commands)
- `api/ExclusionListController.kt` (+DTOs)
- (test) `application/usecase/ExclusionListServiceTest.kt`

**Backend — 수정**
- `application/usecase/EsgScreeningReportGenerator.kt` (exclusionRepo 주입)
- `application/usecase/EsgScreeningReportGeneratorTest.kt` (헬퍼 + 신규 테스트)
- `infra/postgres/init.sql` (2 테이블)
- (신규) `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`

**Frontend (신규/수정)**
- `types/exclusion-list.ts`, `lib/exclusion-list-api.ts`
- `app/unified/reports/esg-screening/lists/page.tsx`
- `app/unified/reports/esg-screening/page.tsx` (링크 추가)

---

## Task 1: 도메인 (ExclusionList, ExclusionItem)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/exclusion/ExclusionItem.kt`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/exclusion/ExclusionList.kt`

- [ ] **Step 1: ExclusionItem**

Create `domain/exclusion/ExclusionItem.kt`:
```kotlin
package com.allfolio.unifiedasset.domain.exclusion

import java.time.LocalDateTime
import java.util.UUID

data class ExclusionItem(
    val id: UUID,
    val listId: UUID,
    val symbol: String,
    val memo: String?,
    val addedAt: LocalDateTime,
)
```

- [ ] **Step 2: ExclusionList**

Create `domain/exclusion/ExclusionList.kt`:
```kotlin
package com.allfolio.unifiedasset.domain.exclusion

import java.time.LocalDateTime
import java.util.UUID

data class ExclusionList(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val category: String,
    val description: String?,
    val active: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val items: List<ExclusionItem> = emptyList(),
)
```

- [ ] **Step 3: 컴파일**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/domain/exclusion/
git commit -m "feat(exclusion): add ExclusionList/ExclusionItem domain"
```

---

## Task 2: 영속성 (엔티티, JPA, 포트, 어댑터)

**Files:**
- Create: `infrastructure/entity/ExclusionListEntity.kt`, `infrastructure/entity/ExclusionItemEntity.kt`
- Create: `infrastructure/jpa/ExclusionListJpaRepository.kt`, `infrastructure/jpa/ExclusionItemJpaRepository.kt`
- Create: `application/port/ExclusionListRepository.kt`
- Create: `infrastructure/repository/ExclusionListRepositoryImpl.kt`

(경로 prefix: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/`)

- [ ] **Step 1: 엔티티**

Create `infrastructure/entity/ExclusionItemEntity.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_exclusion_items")
class ExclusionItemEntity(
    @Id @Column(columnDefinition = "uuid") val id: UUID,
    @Column(name = "list_id", nullable = false, columnDefinition = "uuid") val listId: UUID,
    @Column(nullable = false, length = 40) val symbol: String,
    @Column(length = 300) val memo: String?,
    @Column(name = "added_at", nullable = false) val addedAt: LocalDateTime,
) {
    fun toDomain() = ExclusionItem(id, listId, symbol, memo, addedAt)
    companion object {
        fun fromDomain(i: ExclusionItem) = ExclusionItemEntity(i.id, i.listId, i.symbol, i.memo, i.addedAt)
    }
}
```

Create `infrastructure/entity/ExclusionListEntity.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_exclusion_lists")
class ExclusionListEntity(
    @Id @Column(columnDefinition = "uuid") val id: UUID,
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid") val userId: UUID,
    @Column(nullable = false, length = 100) val name: String,
    @Column(nullable = false, length = 30) val category: String,
    @Column(length = 500) val description: String?,
    @Column(nullable = false) val active: Boolean,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
    @Column(name = "updated_at", nullable = false) val updatedAt: LocalDateTime,
) {
    /** items는 어댑터에서 별도 조회해 채운다. */
    fun toDomain(items: List<ExclusionItem>) =
        ExclusionList(id, userId, name, category, description, active, createdAt, updatedAt, items)
    companion object {
        fun fromDomain(l: ExclusionList) =
            ExclusionListEntity(l.id, l.userId, l.name, l.category, l.description, l.active, l.createdAt, l.updatedAt)
    }
}
```

- [ ] **Step 2: JPA 리포지토리**

Create `infrastructure/jpa/ExclusionListJpaRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.ExclusionListEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExclusionListJpaRepository : JpaRepository<ExclusionListEntity, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<ExclusionListEntity>
    fun findByUserIdAndActiveTrue(userId: UUID): List<ExclusionListEntity>
}
```

Create `infrastructure/jpa/ExclusionItemJpaRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.ExclusionItemEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExclusionItemJpaRepository : JpaRepository<ExclusionItemEntity, UUID> {
    fun findByListIdIn(listIds: Collection<UUID>): List<ExclusionItemEntity>
    fun existsByListIdAndSymbol(listId: UUID, symbol: String): Boolean
}
```

- [ ] **Step 3: 포트**

Create `application/port/ExclusionListRepository.kt`:
```kotlin
package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import java.util.UUID

interface ExclusionListRepository {
    fun findByUser(userId: UUID): List<ExclusionList>        // items 포함
    fun findActiveByUser(userId: UUID): List<ExclusionList>  // items 포함, active만
    fun findById(id: UUID): ExclusionList?                   // items 포함
    fun saveList(list: ExclusionList): ExclusionList         // 메타만 저장(items 제외)
    fun deleteList(id: UUID)                                 // items는 DB cascade
    fun addItem(item: ExclusionItem): ExclusionItem
    fun deleteItem(itemId: UUID)
    fun existsItem(listId: UUID, symbol: String): Boolean
}
```

- [ ] **Step 4: 어댑터**

Create `infrastructure/repository/ExclusionListRepositoryImpl.kt`:
```kotlin
package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import com.allfolio.unifiedasset.infrastructure.entity.ExclusionItemEntity
import com.allfolio.unifiedasset.infrastructure.entity.ExclusionListEntity
import com.allfolio.unifiedasset.infrastructure.jpa.ExclusionItemJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.ExclusionListJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ExclusionListRepositoryImpl(
    private val listJpa: ExclusionListJpaRepository,
    private val itemJpa: ExclusionItemJpaRepository,
) : ExclusionListRepository {

    private fun assemble(lists: List<ExclusionListEntity>): List<ExclusionList> {
        if (lists.isEmpty()) return emptyList()
        val itemsByList = itemJpa.findByListIdIn(lists.map { it.id })
            .groupBy { it.listId }
        return lists.map { l ->
            l.toDomain(itemsByList[l.id].orEmpty().map { it.toDomain() }.sortedBy { it.addedAt })
        }
    }

    override fun findByUser(userId: UUID) =
        assemble(listJpa.findByUserIdOrderByCreatedAtDesc(userId))

    override fun findActiveByUser(userId: UUID) =
        assemble(listJpa.findByUserIdAndActiveTrue(userId))

    override fun findById(id: UUID): ExclusionList? =
        listJpa.findById(id).orElse(null)?.let { assemble(listOf(it)).first() }

    override fun saveList(list: ExclusionList): ExclusionList =
        listJpa.save(ExclusionListEntity.fromDomain(list)).toDomain(emptyList())

    override fun deleteList(id: UUID) = listJpa.deleteById(id)  // items: FK ON DELETE CASCADE

    override fun addItem(item: ExclusionItem): ExclusionItem =
        itemJpa.save(ExclusionItemEntity.fromDomain(item)).toDomain()

    override fun deleteItem(itemId: UUID) = itemJpa.deleteById(itemId)

    override fun existsItem(listId: UUID, symbol: String): Boolean =
        itemJpa.existsByListIdAndSymbol(listId, symbol)
}
```

- [ ] **Step 5: 컴파일 + Commit**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin -q` → BUILD SUCCESSFUL
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/ExclusionListEntity.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/entity/ExclusionItemEntity.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/ExclusionListJpaRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/jpa/ExclusionItemJpaRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/ExclusionListRepository.kt \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/repository/ExclusionListRepositoryImpl.kt
git commit -m "feat(exclusion): add persistence (entities, jpa, port, adapter)"
```

---

## Task 3: 서비스 — CRUD + 소유권 + CSV + 복제 (TDD)

**Files:**
- Create: `application/usecase/ExclusionListService.kt`
- Test: `application/usecase/ExclusionListServiceTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ExclusionListServiceTest.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

class ExclusionListServiceTest {

    private class FakeRepo : ExclusionListRepository {
        val lists = mutableListOf<ExclusionList>()
        val items = mutableListOf<ExclusionItem>()
        private fun withItems(l: ExclusionList) = l.copy(items = items.filter { it.listId == l.id }.sortedBy { it.addedAt })
        override fun findByUser(userId: UUID) = lists.filter { it.userId == userId }.map { withItems(it) }
        override fun findActiveByUser(userId: UUID) = lists.filter { it.userId == userId && it.active }.map { withItems(it) }
        override fun findById(id: UUID) = lists.firstOrNull { it.id == id }?.let { withItems(it) }
        override fun saveList(list: ExclusionList): ExclusionList { lists.removeIf { it.id == list.id }; lists.add(list); return list }
        override fun deleteList(id: UUID) { lists.removeIf { it.id == id }; items.removeIf { it.listId == id } }
        override fun addItem(item: ExclusionItem): ExclusionItem { items.add(item); return item }
        override fun deleteItem(itemId: UUID) { items.removeIf { it.id == itemId } }
        override fun existsItem(listId: UUID, symbol: String) = items.any { it.listId == listId && it.symbol == symbol }
    }

    private val user = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private fun svc(repo: ExclusionListRepository = FakeRepo()) = ExclusionListService(repo) to repo

    @Test
    fun `리스트를 생성하고 조회한다`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("석탄 배제", "석탄", "설명"))
        assertThat(l.name).isEqualTo("석탄 배제")
        assertThat(s.list(user)).hasSize(1)
    }

    @Test
    fun `타유저 리스트 수정은 FORBIDDEN, 없는 리스트는 NOT_FOUND`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("a", "석탄", null))
        assertThatThrownBy { s.update(other, l.id, UpdateListCommand("x", "석탄", null, true)) }
            .isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { s.update(user, UUID.randomUUID(), UpdateListCommand("x", "석탄", null, true)) }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test
    fun `종목 추가는 정규화되고 중복은 무시된다`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("a", "석탄", null))
        s.addItem(user, l.id, AddItemCommand(" excl-coal-01 ", "메모"))
        s.addItem(user, l.id, AddItemCommand("EXCL-COAL-01", null)) // 중복(정규화 후 동일) → 무시
        assertThat(s.list(user).first().items.map { it.symbol }).containsExactly("EXCL-COAL-01")
    }

    @Test
    fun `CSV 반입은 개행 콤마 혼합을 파싱하고 중복 빈줄을 제거한다`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("a", "석탄", null))
        val added = s.importCsv(user, l.id, "AAA,BBB\n aaa \nCCC\n\nBBB")
        assertThat(added).isEqualTo(3) // AAA,BBB,CCC (aaa=AAA 중복, 빈줄 제거)
        assertThat(s.list(user).first().items.map { it.symbol }).containsExactlyInAnyOrder("AAA", "BBB", "CCC")
    }

    @Test
    fun `종목 삭제는 소유 리스트만 가능하다`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("a", "석탄", null))
        val item = s.addItem(user, l.id, AddItemCommand("AAA", null))
        assertThatThrownBy { s.deleteItem(other, l.id, item.id) }.isInstanceOf(ResponseStatusException::class.java)
        s.deleteItem(user, l.id, item.id)
        assertThat(s.list(user).first().items).isEmpty()
    }

    @Test
    fun `프리셋을 복제하면 심볼이 담긴 사용자 리스트가 생긴다`() {
        val (s, _) = svc()
        val cloned = s.clonePreset(user, "예시 프리셋")
        assertThat(cloned.userId).isEqualTo(user)
        assertThat(cloned.items.map { it.symbol }).containsExactlyInAnyOrder("EXCL-COAL-01", "EXCL-WEAPON-01")
    }

    @Test
    fun `프리셋 목록은 내장 프리셋을 그룹으로 반환한다`() {
        val (s, _) = svc()
        val presets = s.presets()
        assertThat(presets).anySatisfy { assertThat(it.name).isEqualTo("예시 프리셋") }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.ExclusionListServiceTest' -q`
Expected: 컴파일 에러(`ExclusionListService`/커맨드/응답 미존재).

- [ ] **Step 3: 서비스 구현**

Create `application/usecase/ExclusionListService.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.UUID

data class CreateListCommand(val name: String, val category: String, val description: String?)
data class UpdateListCommand(val name: String, val category: String, val description: String?, val active: Boolean)
data class AddItemCommand(val symbol: String, val memo: String?)
data class PresetView(val name: String, val symbols: List<PresetSymbol>)
data class PresetSymbol(val symbol: String, val reason: String)

@Service
class ExclusionListService(
    private val repository: ExclusionListRepository,
) {
    fun list(userId: UUID): List<ExclusionList> = repository.findByUser(userId)

    fun presets(): List<PresetView> =
        EsgExclusionPreset.entries.entries
            .groupBy { it.value.listName }
            .map { (listName, es) -> PresetView(listName, es.map { PresetSymbol(it.key, it.value.reason) }) }

    @Transactional
    fun create(userId: UUID, cmd: CreateListCommand): ExclusionList {
        validateName(cmd.name)
        val now = LocalDateTime.now()
        return repository.saveList(
            ExclusionList(UUID.randomUUID(), userId, cmd.name.trim(), cmd.category.trim(),
                cmd.description?.trim()?.takeIf { it.isNotBlank() }, true, now, now),
        )
    }

    @Transactional
    fun update(userId: UUID, id: UUID, cmd: UpdateListCommand): ExclusionList {
        val existing = owned(userId, id)
        validateName(cmd.name)
        return repository.saveList(
            existing.copy(name = cmd.name.trim(), category = cmd.category.trim(),
                description = cmd.description?.trim()?.takeIf { it.isNotBlank() },
                active = cmd.active, updatedAt = LocalDateTime.now()),
        )
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) {
        owned(userId, id)
        repository.deleteList(id)
    }

    @Transactional
    fun addItem(userId: UUID, listId: UUID, cmd: AddItemCommand): ExclusionItem {
        owned(userId, listId)
        val symbol = normalizeSymbol(cmd.symbol)
        // 중복이면 기존 반환(무시)
        if (repository.existsItem(listId, symbol)) {
            return repository.findById(listId)!!.items.first { it.symbol == symbol }
        }
        return repository.addItem(
            ExclusionItem(UUID.randomUUID(), listId, symbol, cmd.memo?.trim()?.takeIf { it.isNotBlank() }, LocalDateTime.now()),
        )
    }

    @Transactional
    fun deleteItem(userId: UUID, listId: UUID, itemId: UUID) {
        val list = owned(userId, listId)
        if (list.items.none { it.id == itemId }) throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다.")
        repository.deleteItem(itemId)
    }

    @Transactional
    fun importCsv(userId: UUID, listId: UUID, csv: String): Int {
        owned(userId, listId)
        val symbols = csv.split('\n', ',').map { normalizeSymbol(it) }.filter { it.isNotBlank() }.distinct()
        var added = 0
        val now = LocalDateTime.now()
        for (s in symbols) {
            if (!repository.existsItem(listId, s)) {
                repository.addItem(ExclusionItem(UUID.randomUUID(), listId, s, null, now)); added++
            }
        }
        return added
    }

    @Transactional
    fun clonePreset(userId: UUID, presetName: String): ExclusionList {
        val preset = presets().firstOrNull { it.name == presetName }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "프리셋을 찾을 수 없습니다.")
        val now = LocalDateTime.now()
        val list = repository.saveList(
            ExclusionList(UUID.randomUUID(), userId, "${preset.name} (복제)",
                preset.symbols.firstOrNull()?.reason ?: "사용자지정", "내장 프리셋 복제", true, now, now),
        )
        preset.symbols.forEach { ps ->
            repository.addItem(ExclusionItem(UUID.randomUUID(), list.id, ps.symbol, ps.reason, now))
        }
        return repository.findById(list.id)!!
    }

    private fun owned(userId: UUID, id: UUID): ExclusionList {
        val l = repository.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "리스트를 찾을 수 없습니다.")
        if (l.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.")
        return l
    }

    private fun validateName(name: String) {
        if (name.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이름은 필수입니다.")
    }

    private fun normalizeSymbol(s: String): String = s.trim().uppercase()
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.ExclusionListServiceTest' -q`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ExclusionListService.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ExclusionListServiceTest.kt
git commit -m "feat(exclusion): add ExclusionListService (CRUD, ownership, CSV, clone)"
```

---

## Task 4: REST 컨트롤러

**Files:**
- Create: `api/ExclusionListController.kt`

> 사용자 CRUD 컨트롤러 관례(GoalController)에 슬라이스 테스트가 없음 — 소유권은 서비스 테스트(Task 3)가 커버. 본 태스크는 얇은 위임 + 컴파일 검증.

- [ ] **Step 1: 컨트롤러 + DTO 구현**

Create `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ExclusionListController.kt`:
```kotlin
package com.allfolio.unifiedasset.api

import com.allfolio.unifiedasset.application.usecase.AddItemCommand
import com.allfolio.unifiedasset.application.usecase.CreateListCommand
import com.allfolio.unifiedasset.application.usecase.ExclusionListService
import com.allfolio.unifiedasset.application.usecase.PresetView
import com.allfolio.unifiedasset.application.usecase.UpdateListCommand
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/exclusion-lists")
class ExclusionListController(private val svc: ExclusionListService) {

    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): List<ExclusionListResponse> =
        svc.list(userId).map { it.toResponse() }

    @GetMapping("/presets")
    fun presets(): List<PresetView> = svc.presets()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: CreateListRequest): ExclusionListResponse =
        svc.create(userId, CreateListCommand(req.name, req.category, req.description)).toResponse()

    @PutMapping("/{id}")
    fun update(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: UpdateListRequest): ExclusionListResponse =
        svc.update(userId, id, UpdateListCommand(req.name, req.category, req.description, req.active)).toResponse()

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID) = svc.delete(userId, id)

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun addItem(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: AddItemRequest): ExclusionItemResponse =
        svc.addItem(userId, id, AddItemCommand(req.symbol, req.memo)).let { ExclusionItemResponse(it.id, it.symbol, it.memo, it.addedAt) }

    @DeleteMapping("/{id}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteItem(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @PathVariable itemId: UUID) =
        svc.deleteItem(userId, id, itemId)

    @PostMapping("/{id}/items/import")
    fun importCsv(@RequestHeader("X-User-Id") userId: UUID, @PathVariable id: UUID, @RequestBody req: ImportCsvRequest): ImportResult =
        ImportResult(svc.importCsv(userId, id, req.csv))

    @PostMapping("/presets/clone")
    @ResponseStatus(HttpStatus.CREATED)
    fun clonePreset(@RequestHeader("X-User-Id") userId: UUID, @RequestBody req: ClonePresetRequest): ExclusionListResponse =
        svc.clonePreset(userId, req.presetName).toResponse()

    private fun ExclusionList.toResponse() = ExclusionListResponse(
        id, name, category, description, active, items.size,
        items.map { ExclusionItemResponse(it.id, it.symbol, it.memo, it.addedAt) }, updatedAt,
    )
}

data class CreateListRequest(val name: String, val category: String, val description: String?)
data class UpdateListRequest(val name: String, val category: String, val description: String?, val active: Boolean)
data class AddItemRequest(val symbol: String, val memo: String?)
data class ImportCsvRequest(val csv: String)
data class ClonePresetRequest(val presetName: String)
data class ImportResult(val added: Int)
data class ExclusionItemResponse(val id: UUID, val symbol: String, val memo: String?, val addedAt: LocalDateTime)
data class ExclusionListResponse(
    val id: UUID, val name: String, val category: String, val description: String?,
    val active: Boolean, val itemCount: Int, val items: List<ExclusionItemResponse>, val updatedAt: LocalDateTime,
)
```

- [ ] **Step 2: 컴파일**

Run: `cd allfolio-backend && ./gradlew :unified-asset:compileKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ExclusionListController.kt
git commit -m "feat(exclusion): add USER exclusion-list REST controller"
```

---

## Task 5: 생성기 통합 (TDD)

**Files:**
- Modify: `application/usecase/EsgScreeningReportGenerator.kt`
- Modify: `application/usecase/EsgScreeningReportGeneratorTest.kt`

- [ ] **Step 1: 테스트 헬퍼 갱신 + 신규 실패 테스트 추가**

Modify `EsgScreeningReportGeneratorTest.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.ExclusionListRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionItem
import com.allfolio.unifiedasset.domain.exclusion.ExclusionList
import java.time.LocalDateTime
```
2. 클래스 안에 fake 배제 repo + 헬퍼 오버로드 추가(기존 `generator(assets)`는 빈 repo로 위임):
```kotlin
    private class FakeExclusionRepo(private val lists: List<ExclusionList>) : ExclusionListRepository {
        override fun findByUser(userId: java.util.UUID) = lists.filter { it.userId == userId }
        override fun findActiveByUser(userId: java.util.UUID) = lists.filter { it.userId == userId && it.active }
        override fun findById(id: java.util.UUID) = lists.firstOrNull { it.id == id }
        override fun saveList(list: ExclusionList) = list
        override fun deleteList(id: java.util.UUID) {}
        override fun addItem(item: ExclusionItem) = item
        override fun deleteItem(itemId: java.util.UUID) {}
        override fun existsItem(listId: java.util.UUID, symbol: String) = false
    }

    private fun userList(active: Boolean, owner: java.util.UUID, vararg symbols: String): ExclusionList {
        val lid = java.util.UUID.randomUUID()
        val now = LocalDateTime.now()
        return ExclusionList(lid, owner, "내 리스트", "사용자지정", null, active, now, now,
            symbols.map { ExclusionItem(java.util.UUID.randomUUID(), lid, it, null, now) })
    }
```
3. 기존 `generator(assets)` 헬퍼를 수정 + 오버로드 추가:
```kotlin
    private fun generator(assets: List<Asset>, exclusion: ExclusionListRepository = FakeExclusionRepo(emptyList())) =
        EsgScreeningReportGenerator(FakeAssetRepo(assets), fx, exclusion)
```
4. 신규 테스트 추가(클래스 끝, 마지막 `}` 앞):
```kotlin
    @Test
    fun `user active list flags a held symbol as violation`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(true, userId, "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(1, body["screening"]["violationCount"].asInt())
        assertEquals("005930", body["violations"][0]["symbol"].asText())
    }

    @Test
    fun `inactive user list is ignored`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(false, userId, "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
    }

    @Test
    fun `other users list is ignored`() {
        val assets = listOf(asset("삼성전자", "005930", "8000000"))
        val repo = FakeExclusionRepo(listOf(userList(true, java.util.UUID.randomUUID(), "005930")))
        val body = mapper.readTree(generator(assets, repo).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
    }
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.EsgScreeningReportGeneratorTest' -q`
Expected: 컴파일 에러(생성자 3-arg 미존재).

- [ ] **Step 3: 생성기 수정**

Modify `application/usecase/EsgScreeningReportGenerator.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.ExclusionListRepository
```
2. 생성자에 파라미터 추가:
```kotlin
@Component
class EsgScreeningReportGenerator(
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
    private val exclusionRepo: ExclusionListRepository,
) : ReportBodyGenerator {
```
3. `generate`의 `violated` 계산 직전에 lookup 구성(내장 프리셋 base + 유저 active 리스트 overlay), `violated`가 이를 쓰도록 교체:
```kotlin
            // 내장 프리셋 ∪ 유저 active 리스트 (같은 symbol이면 유저 리스트 우선)
            val lookup = LinkedHashMap<String, Pair<String, String>>() // symbol -> (listName, reason)
            EsgExclusionPreset.entries.forEach { (sym, ex) -> lookup[sym] = ex.listName to ex.reason }
            exclusionRepo.findActiveByUser(userId).forEach { list ->
                list.items.forEach { it -> lookup[it.symbol] = list.name to list.category }
            }

            val violated = valued.mapNotNull { (a, v) ->
                a.symbol?.let { sym -> lookup[sym]?.let { (ln, rs) -> Quad(a, v, ln, rs) } }
            }.sortedByDescending { it.value }
            val violationValueKrw = violated.fold(BigDecimal.ZERO) { acc, t -> acc + t.value }
            val violations = violated.map { q ->
                mapOf("name" to q.asset.name, "symbol" to q.asset.symbol, "listName" to q.listName,
                    "reason" to q.reason, "valueKrw" to q.value, "weight" to pct(q.value, totalKrw))
            }
```
4. 파일 하단(클래스 밖 또는 private)에 헬퍼 데이터 클래스 추가:
```kotlin
private data class Quad(val asset: com.allfolio.unifiedasset.domain.asset.Asset, val value: BigDecimal, val listName: String, val reason: String)
```
> 기존 `EsgExclusionPreset.lookup` 기반 `violated`/`violations` 블록을 위 내용으로 대체. `screening` 집계·본문 나머지는 그대로.

- [ ] **Step 4: 통과 확인 (신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.EsgScreeningReportGeneratorTest' -q`
Expected: PASS (기존 프리셋 테스트 + 신규 3개 모두).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGeneratorTest.kt
git commit -m "feat(exclusion): screen against user active lists in EsgScreeningReportGenerator"
```

---

## Task 6: 스키마 (init.sql) + 운영 마이그레이션 SQL

**Files:**
- Modify: `allfolio-backend/infra/postgres/init.sql`
- Create: `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`

- [ ] **Step 1: init.sql에 두 테이블 추가**

`allfolio-backend/infra/postgres/init.sql`의 ua_ 구역(예: `ua_goals` 블록 뒤)에 추가:
```sql
-- ── ua_exclusion_lists / ua_exclusion_items : R-07 사용자 배제리스트 ──────
CREATE TABLE IF NOT EXISTS ua_exclusion_lists (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(30)   NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_lists PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_lists_user ON ua_exclusion_lists (user_id);

CREATE TABLE IF NOT EXISTS ua_exclusion_items (
    id       UUID         NOT NULL,
    list_id  UUID         NOT NULL,
    symbol   VARCHAR(40)  NOT NULL,
    memo     VARCHAR(300),
    added_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_items PRIMARY KEY (id),
    CONSTRAINT fk_ua_exclusion_items_list FOREIGN KEY (list_id)
        REFERENCES ua_exclusion_lists(id) ON DELETE CASCADE,
    CONSTRAINT uk_ua_exclusion_items UNIQUE (list_id, symbol)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_items_list ON ua_exclusion_items (list_id);
```

- [ ] **Step 2: 운영 마이그레이션 SQL 생성**

Create `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`:
```sql
-- R-07 사용자 배제리스트 (SCR-RPT-11) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-30-exclusion-list.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등. 시드 없음(사용자 데이터).

CREATE TABLE IF NOT EXISTS ua_exclusion_lists (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(30)   NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_lists PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_lists_user ON ua_exclusion_lists (user_id);

CREATE TABLE IF NOT EXISTS ua_exclusion_items (
    id       UUID         NOT NULL,
    list_id  UUID         NOT NULL,
    symbol   VARCHAR(40)  NOT NULL,
    memo     VARCHAR(300),
    added_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_items PRIMARY KEY (id),
    CONSTRAINT fk_ua_exclusion_items_list FOREIGN KEY (list_id)
        REFERENCES ua_exclusion_lists(id) ON DELETE CASCADE,
    CONSTRAINT uk_ua_exclusion_items UNIQUE (list_id, symbol)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_items_list ON ua_exclusion_items (list_id);

-- 검증
SELECT to_regclass('ua_exclusion_lists') AS lists, to_regclass('ua_exclusion_items') AS items;
```

- [ ] **Step 3: Commit**

```bash
git add allfolio-backend/infra/postgres/init.sql docs/superpowers/migrations/2026-07-30-exclusion-list.sql
git commit -m "feat(exclusion): add ua_exclusion_lists/items schema and prod migration SQL"
```

---

## Task 7: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `ExclusionListServiceTest`(7) + 확장 `EsgScreeningReportGeneratorTest`(기존+3), 기존 회귀 없음.

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(exclusion): fix regressions"
```

---

## Task 8: FE — 타입 + API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/exclusion-list.ts`
- Create: `frontend/allfolio_app/lib/exclusion-list-api.ts`

- [ ] **Step 1: 타입**

Create `frontend/allfolio_app/types/exclusion-list.ts`:
```ts
export interface ExclusionItem {
  id: string
  symbol: string
  memo: string | null
  addedAt: string
}

export interface ExclusionList {
  id: string
  name: string
  category: string
  description: string | null
  active: boolean
  itemCount: number
  items: ExclusionItem[]
  updatedAt: string
}

export interface PresetSymbol { symbol: string; reason: string }
export interface Preset { name: string; symbols: PresetSymbol[] }

export interface CreateList { name: string; category: string; description?: string | null }
export interface UpdateList { name: string; category: string; description?: string | null; active: boolean }
```

- [ ] **Step 2: API 클라이언트**

Create `frontend/allfolio_app/lib/exclusion-list-api.ts`:
```ts
import axios from 'axios'
import type { ExclusionList, ExclusionItem, Preset, CreateList, UpdateList } from '@/types/exclusion-list'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/exclusion-lists`

export function createExclusionListApi(accessToken: string) {
  const api = axios.create({ baseURL: BASE_URL, timeout: 30_000, headers: { Authorization: `Bearer ${accessToken}` } })
  return {
    list: async (): Promise<ExclusionList[]> => (await api.get<ExclusionList[]>('')).data,
    presets: async (): Promise<Preset[]> => (await api.get<Preset[]>('/presets')).data,
    create: async (body: CreateList): Promise<ExclusionList> => (await api.post<ExclusionList>('', body)).data,
    update: async (id: string, body: UpdateList): Promise<ExclusionList> => (await api.put<ExclusionList>(`/${id}`, body)).data,
    remove: async (id: string): Promise<void> => { await api.delete(`/${id}`) },
    addItem: async (id: string, symbol: string, memo?: string): Promise<ExclusionItem> =>
      (await api.post<ExclusionItem>(`/${id}/items`, { symbol, memo: memo ?? null })).data,
    removeItem: async (id: string, itemId: string): Promise<void> => { await api.delete(`/${id}/items/${itemId}`) },
    importCsv: async (id: string, csv: string): Promise<{ added: number }> =>
      (await api.post<{ added: number }>(`/${id}/items/import`, { csv })).data,
    clonePreset: async (presetName: string): Promise<ExclusionList> =>
      (await api.post<ExclusionList>('/presets/clone', { presetName })).data,
  }
}
```

- [ ] **Step 3: 타입 체크 + Commit**

Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음
```bash
git add frontend/allfolio_app/types/exclusion-list.ts frontend/allfolio_app/lib/exclusion-list-api.ts
git commit -m "feat(exclusion): add FE types and API client"
```

---

## Task 9: FE — 관리 화면 + 진입 링크

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/esg-screening/lists/page.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/esg-screening/page.tsx`

- [ ] **Step 1: 관리 화면**

Create `frontend/allfolio_app/app/unified/reports/esg-screening/lists/page.tsx`:
```tsx
'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { createExclusionListApi } from '@/lib/exclusion-list-api'
import type { ExclusionList, Preset } from '@/types/exclusion-list'

export default function ExclusionListsPage() {
  const { accessToken, initialized, authenticated } = useAuth()
  const [lists, setLists] = useState<ExclusionList[]>([])
  const [presets, setPresets] = useState<Preset[]>([])
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ name: '', category: '사용자지정', description: '' })
  const [itemInput, setItemInput] = useState<Record<string, string>>({})

  const api = useMemo(() => (accessToken ? createExclusionListApi(accessToken) : null), [accessToken])

  const refetch = useCallback(async () => {
    if (!api) return
    try {
      const [ls, ps] = await Promise.all([api.list(), api.presets()])
      setLists(ls); setPresets(ps)
    } catch { setError('배제리스트를 불러오지 못했습니다.') }
  }, [api])

  useEffect(() => { if (initialized && authenticated) refetch() }, [initialized, authenticated, refetch])

  const wrap = async (fn: () => Promise<unknown>, msg: string) => {
    if (!api) return
    setError(null)
    try { await fn(); await refetch() } catch { setError(msg) }
  }

  const createList = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim()) return
    void wrap(() => api!.create({ name: form.name, category: form.category, description: form.description || null }), '생성 실패')
    setForm({ name: '', category: '사용자지정', description: '' })
  }

  const onCsv = (id: string) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]; if (!file) return
    const reader = new FileReader()
    reader.onload = () => void wrap(() => api!.importCsv(id, String(reader.result ?? '')), 'CSV 가져오기 실패')
    reader.readAsText(file)
    e.target.value = ''
  }

  if (!initialized) return <div className="p-6 text-gray-400">로딩 중…</div>
  if (!authenticated) return <div className="p-6 text-gray-400">로그인이 필요합니다.</div>

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-6">
      <div className="flex items-center gap-3">
        <Link href="/unified/reports/esg-screening" className="text-sm text-gray-400 hover:text-white">← ESG 스크리닝</Link>
        <h1 className="text-2xl font-bold">배제리스트 관리</h1>
      </div>
      {error && <div className="rounded bg-red-900/40 px-3 py-2 text-sm text-red-300">{error}</div>}

      {/* 내장 프리셋 */}
      <section>
        <h2 className="mb-2 text-lg font-semibold">내장 프리셋 <span className="text-xs text-gray-400">(읽기전용 · 복제 후 편집)</span></h2>
        <div className="flex flex-wrap gap-2">
          {presets.map(p => (
            <button key={p.name} onClick={() => void wrap(() => api!.clonePreset(p.name), '복제 실패')}
              className="rounded border border-amber-700 bg-amber-900/20 px-3 py-1.5 text-sm text-amber-300 hover:bg-amber-900/40">
              {p.name} ({p.symbols.length}) · 복제
            </button>
          ))}
        </div>
      </section>

      {/* 생성 폼 */}
      <form onSubmit={createList} className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 p-4">
        <label className="text-sm">이름
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.name} required
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
        </label>
        <label className="text-sm">카테고리
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.category}
            onChange={e => setForm(f => ({ ...f, category: e.target.value }))} />
        </label>
        <label className="text-sm">설명
          <input className="ml-2 rounded bg-gray-800 px-2 py-1" value={form.description}
            onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
        </label>
        <button type="submit" className="rounded bg-blue-600 px-4 py-1.5 text-sm font-medium hover:bg-blue-500">리스트 생성</button>
      </form>

      {/* 리스트들 */}
      <section className="space-y-4">
        {lists.map(l => (
          <div key={l.id} className="rounded-lg border border-gray-800 p-4">
            <div className="mb-2 flex items-center gap-3">
              <span className="font-semibold">{l.name}</span>
              <span className="text-xs text-gray-400">{l.category} · {l.itemCount}종목</span>
              <label className="ml-auto flex items-center gap-1 text-xs text-gray-400">
                <input type="checkbox" checked={l.active}
                  onChange={() => void wrap(() => api!.update(l.id, { name: l.name, category: l.category, description: l.description, active: !l.active }), '토글 실패')} />
                활성
              </label>
              <label className="cursor-pointer text-xs text-blue-400 hover:text-blue-300">
                CSV 가져오기<input type="file" accept=".csv,text/csv,text/plain" className="hidden" onChange={onCsv(l.id)} />
              </label>
              <button onClick={() => void wrap(() => api!.remove(l.id), '삭제 실패')} className="text-xs text-red-400 hover:text-red-300">리스트 삭제</button>
            </div>
            {/* 종목 추가 */}
            <div className="mb-2 flex gap-2">
              <input placeholder="심볼 추가 (예: 005930)" className="rounded bg-gray-800 px-2 py-1 text-sm"
                value={itemInput[l.id] ?? ''} onChange={e => setItemInput(m => ({ ...m, [l.id]: e.target.value }))} />
              <button onClick={() => { const s = itemInput[l.id]?.trim(); if (s) { void wrap(() => api!.addItem(l.id, s), '추가 실패'); setItemInput(m => ({ ...m, [l.id]: '' })) } }}
                className="rounded bg-gray-700 px-3 py-1 text-sm hover:bg-gray-600">추가</button>
            </div>
            <div className="flex flex-wrap gap-2">
              {l.items.map(it => (
                <span key={it.id} className="flex items-center gap-1 rounded bg-gray-800 px-2 py-1 text-xs">
                  {it.symbol}
                  <button onClick={() => void wrap(() => api!.removeItem(l.id, it.id), '삭제 실패')} className="text-red-400 hover:text-red-300">×</button>
                </span>
              ))}
              {l.items.length === 0 && <span className="text-xs text-gray-500">종목 없음</span>}
            </div>
          </div>
        ))}
        {lists.length === 0 && <div className="text-sm text-gray-500">리스트가 없습니다. 위에서 생성하거나 프리셋을 복제하세요.</div>}
      </section>
    </div>
  )
}
```

- [ ] **Step 2: esg-screening 화면에 진입 링크 추가**

Modify `frontend/allfolio_app/app/unified/reports/esg-screening/page.tsx`: `<h1 ...>ESG 스크리닝</h1>` 가 있는 헤더 영역에 관리 링크를 추가. 파일 상단에 `import Link from 'next/link'`가 없으면 추가하고, h1 근처(제목 줄)에:
```tsx
        <Link href="/unified/reports/esg-screening/lists" className="text-sm text-amber-400 hover:text-amber-300">배제리스트 관리 →</Link>
```
(기존 레이아웃의 제목 영역에 자연스럽게 배치. h1과 같은 줄이 어려우면 h1 아래 별도 줄로.)

- [ ] **Step 3: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음. (`Map.entries()`/이터레이터 스프레드는 `Array.from(...)` 사용 — 이 프로젝트 tsconfig는 downlevelIteration 미설정.)

- [ ] **Step 4: Commit**

```bash
git add frontend/allfolio_app/app/unified/reports/esg-screening/lists/page.tsx \
        frontend/allfolio_app/app/unified/reports/esg-screening/page.tsx
git commit -m "feat(exclusion): add exclusion-list management screen and entry link"
```

---

## Task 10: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 실 Postgres 16에 init.sql 적용 → 테이블·cascade·unique 검증**

```bash
cd /Users/hong9/IdeaProjects/allfolio
CID=$(docker run -d --rm -e POSTGRES_DB=allfolio -e POSTGRES_USER=allfolio -e POSTGRES_PASSWORD=allfolio \
  -v "$PWD/allfolio-backend/infra/postgres/init.sql":/docker-entrypoint-initdb.d/init.sql:ro postgres:16-alpine)
for i in $(seq 1 30); do docker exec "$CID" pg_isready -U allfolio -d allfolio >/dev/null 2>&1 && break; sleep 1; done
sleep 3
echo "=== 테이블 존재 ==="; docker exec "$CID" psql -U allfolio -d allfolio -tAc "SELECT to_regclass('ua_exclusion_lists'), to_regclass('ua_exclusion_items');"
echo "=== 리스트+종목 삽입, cascade 삭제 ==="
docker exec "$CID" psql -U allfolio -d allfolio -c "INSERT INTO ua_exclusion_lists(id,user_id,name,category) VALUES ('11111111-1111-1111-1111-111111111111',gen_random_uuid(),'t','석탄');"
docker exec "$CID" psql -U allfolio -d allfolio -c "INSERT INTO ua_exclusion_items(id,list_id,symbol) VALUES (gen_random_uuid(),'11111111-1111-1111-1111-111111111111','AAA');"
echo "-- uk 중복 차단 --"; docker exec "$CID" psql -U allfolio -d allfolio -c "INSERT INTO ua_exclusion_items(id,list_id,symbol) VALUES (gen_random_uuid(),'11111111-1111-1111-1111-111111111111','AAA');" 2>&1 | grep -iE "ERROR|uk_ua_exclusion" | head -1
docker exec "$CID" psql -U allfolio -d allfolio -c "DELETE FROM ua_exclusion_lists WHERE id='11111111-1111-1111-1111-111111111111';"
echo "-- cascade: items 잔존 0 이어야 --"; docker exec "$CID" psql -U allfolio -d allfolio -tAc "SELECT count(*) FROM ua_exclusion_items;"
docker stop "$CID" >/dev/null && echo "stopped"
```
Expected: 두 테이블 존재, uk 중복 ERROR, cascade 후 items count=0.

- [ ] **Step 2: (앱 레벨) 서비스/생성기 테스트가 CRUD·소유권·CSV·스크리닝을 커버함을 확인**

Task 3(서비스 7) + Task 5(생성기 통합 3) + Task 7 전체 회귀가 로직/통합을 검증. 실 부팅 불필요. 결과 요약 보고.

- [ ] **Step 3: 검증 결과 기록 (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
1. `docs/superpowers/migrations/2026-07-30-exclusion-list.sql`을 **운영 Neon에 BE 배포 전 수동 실행**(테이블 2개, 무해·멱등).
2. main 병합 → Render 자동배포(BE) → FE 배포.
3. 로그인 → ESG 스크리닝 → "배제리스트 관리" → 리스트 생성/종목추가/CSV/활성토글/프리셋 복제 → 보유 심볼을 active 리스트에 넣고 ESG 리포트 재생성 시 violations 반영 확인.

---

## Notes / 주의
- 서비스는 포트(`ExclusionListRepository`) 의존 → DB 없이 fake 단위테스트.
- 소유권은 서비스에서 `ResponseStatusException(NOT_FOUND/FORBIDDEN)` (GoalService의 `error()/require()`보다 명시적 HTTP 코드).
- `deleteList`는 `deleteById` + DB `ON DELETE CASCADE`로 items 제거(별도 삭제 쿼리 불필요).
- symbol은 `trim().uppercase()` 정규화(매칭·중복 판정 일관). uk(list_id, symbol)로 DB에서도 중복 방어.
- 컨트롤러 DTO의 LocalDateTime은 Spring MVC Jackson으로 정상 직렬화(report 본문 jacksonObjectMapper 함정 무관).
- 범위 밖(후속): ISIN 정밀매칭·위반이력·감시로그(SCR-RPT-10), 프리셋 ADMIN 큐레이션(DB화), 유효기간 버저닝.
```
