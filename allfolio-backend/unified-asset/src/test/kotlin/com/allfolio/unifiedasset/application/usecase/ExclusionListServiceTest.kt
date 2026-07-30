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
    fun `수정 응답은 종목을 포함해 반환한다`() {
        val (s, _) = svc()
        val l = s.create(user, CreateListCommand("a", "석탄", null))
        s.addItem(user, l.id, AddItemCommand("AAA", null))
        val updated = s.update(user, l.id, UpdateListCommand("a", "석탄", null, false))
        assertThat(updated.active).isFalse()
        assertThat(updated.items.map { it.symbol }).containsExactly("AAA")
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
