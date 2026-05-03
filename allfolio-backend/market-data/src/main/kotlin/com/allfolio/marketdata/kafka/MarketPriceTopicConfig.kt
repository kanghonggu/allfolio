package com.allfolio.marketdata.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * market.prices 토픽 자동 생성
 *
 * partitions=12: 고빈도 시세 데이터, exchange:symbol 해시로 분배
 * retention=1h:  실시간 PnL 계산 목적 — 장기 보존은 DB(market_price_tick)
 * compression=lz4: JSON 숫자 데이터 압축률 good, CPU 부하 낮음
 * replicas=1: 단일 노드 기본값 (운영 시 3으로 변경)
 */
@Configuration
class MarketPriceTopicConfig {

    @Bean
    fun marketPricesTopic(): NewTopic = TopicBuilder.name(TopicConstants.MARKET_PRICES)
        .partitions(12)
        .replicas(1)
        .config(TopicConfig.RETENTION_MS_CONFIG, "${60 * 60 * 1000}")        // 1시간
        .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4")
        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "1")
        .build()
}
