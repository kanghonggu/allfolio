package com.allfolio.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Kafka DLQ Topic 자동 생성
 *
 * ConditionalOnProperty: spring.kafka.bootstrap-servers 설정 시에만 활성화
 * partitions=3: DLQ Worker 병렬 처리 지원
 * replicas=1: 단일 노드 기본값 (운영 시 3으로 증가)
 */
@Configuration
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"], matchIfMissing = false)
class KafkaTopicConfig {

    @Bean
    fun dlqTradeTopic(): NewTopic = TopicBuilder.name(DLQ_TRADE)
        .partitions(3).replicas(1).build()

    @Bean
    fun dlqFetchTopic(): NewTopic = TopicBuilder.name(DLQ_FETCH)
        .partitions(3).replicas(1).build()

    @Bean
    fun dlqDeadTopic(): NewTopic = TopicBuilder.name(DLQ_DEAD)
        .partitions(1).replicas(1)
        .config("retention.ms", "${7 * 24 * 60 * 60 * 1000}")  // 7일
        .build()

    /**
     * Outbox 이벤트 전파 토픽
     * - partitions=6: 포트폴리오 수 기준 충분한 병렬성
     * - retention=24h: Outbox가 Source of Truth이므로 단기 보존으로 충분
     */
    @Bean
    fun outboxTradeTopic(): NewTopic = TopicBuilder.name(OUTBOX_TRADE)
        .partitions(6).replicas(1)
        .config("retention.ms", "${24 * 60 * 60 * 1000}")  // 24시간
        .build()

    companion object {
        const val DLQ_TRADE    = "dlq.trade"
        const val DLQ_FETCH    = "dlq.fetch"
        const val DLQ_DEAD     = "dlq.dead"
        const val OUTBOX_TRADE = "outbox.trade"
    }
}
