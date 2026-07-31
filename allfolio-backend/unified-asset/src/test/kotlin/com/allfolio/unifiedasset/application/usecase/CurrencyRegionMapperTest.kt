package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CurrencyRegionMapperTest {
    @Test
    fun `통화코드는 지역으로 매핑된다`() {
        assertThat(CurrencyRegionMapper.regionOf("KRW")).isEqualTo("국내")
        assertThat(CurrencyRegionMapper.regionOf("USD")).isEqualTo("미국")
        assertThat(CurrencyRegionMapper.regionOf("JPY")).isEqualTo("일본")
        assertThat(CurrencyRegionMapper.regionOf("EUR")).isEqualTo("유럽")
    }

    @Test
    fun `소문자·공백은 정규화된다`() {
        assertThat(CurrencyRegionMapper.regionOf("usd")).isEqualTo("미국")
        assertThat(CurrencyRegionMapper.regionOf(" krw ")).isEqualTo("국내")
    }

    @Test
    fun `미등록 통화와 null-공백은 기타`() {
        assertThat(CurrencyRegionMapper.regionOf("AUD")).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf(null)).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf("")).isEqualTo("기타")
        assertThat(CurrencyRegionMapper.regionOf("   ")).isEqualTo("기타")
    }
}
