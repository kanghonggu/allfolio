package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.ExclusionPresetRepository
import com.allfolio.unifiedasset.domain.exclusion.ExclusionPreset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ExclusionPresetServiceTest {

    private class FakeRepo : ExclusionPresetRepository {
        val store = mutableMapOf<UUID, ExclusionPreset>()
        override fun findAll() = store.values.toList()
        override fun findBySymbol(symbol: String) = store.values.firstOrNull { it.symbol == symbol }
        override fun save(preset: ExclusionPreset): ExclusionPreset {
            store[preset.id] = preset
            return preset
        }
        override fun delete(id: UUID) {
            store.remove(id)
        }
    }

    private val admin = UUID.randomUUID()

    private fun service(repo: ExclusionPresetRepository = FakeRepo()) = ExclusionPresetService(repo) to repo

    @Test
    fun `신규 심볼은 대문자로 정규화되어 insert된다`() {
        val (svc, repo) = service()
        val saved = svc.upsert(UpsertPresetCommand("excl-x", "리스트A", "사유A"), admin)
        assertThat(saved.symbol).isEqualTo("EXCL-X")
        assertThat(saved.listName).isEqualTo("리스트A")
        assertThat(saved.reason).isEqualTo("사유A")
        assertThat(saved.updatedBy).isEqualTo(admin)
        assertThat(repo.findAll()).hasSize(1)
    }

    @Test
    fun `동일 symbol 재upsert는 update되고 id가 유지된다`() {
        val (svc, repo) = service()
        val first = svc.upsert(UpsertPresetCommand("EXCL-X", "리스트A", "사유A"), admin)
        val second = svc.upsert(UpsertPresetCommand("excl-x", "리스트B", "사유B"), admin)
        assertThat(second.id).isEqualTo(first.id)
        assertThat(second.listName).isEqualTo("리스트B")
        assertThat(second.reason).isEqualTo("사유B")
        assertThat(repo.findAll()).hasSize(1)
    }

    @Test
    fun `빈 symbol이면 IllegalArgumentException`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.upsert(UpsertPresetCommand("  ", "리스트A", "사유A"), admin) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `빈 listName이면 IllegalArgumentException`() {
        val (svc, _) = service()
        assertThatThrownBy { svc.upsert(UpsertPresetCommand("EXCL-X", "  ", "사유A"), admin) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `delete는 프리셋을 제거한다`() {
        val (svc, repo) = service()
        val saved = svc.upsert(UpsertPresetCommand("EXCL-X", "리스트A", "사유A"), admin)
        svc.delete(saved.id)
        assertThat(repo.findAll()).isEmpty()
    }

    @Test
    fun `list는 전체 프리셋을 반환한다`() {
        val (svc, _) = service()
        svc.upsert(UpsertPresetCommand("EXCL-X", "리스트A", "사유A"), admin)
        svc.upsert(UpsertPresetCommand("EXCL-Y", "리스트B", "사유B"), admin)
        assertThat(svc.list()).hasSize(2)
    }
}
