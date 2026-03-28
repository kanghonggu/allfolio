package com.allfolio.snapshot.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    @Bean
    fun snapshotObjectMapper(): ObjectMapper = ObjectMapper()
        .registerModule(kotlinModule())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Bean
    fun redisTemplate(
        connectionFactory: RedisConnectionFactory,
        snapshotObjectMapper: ObjectMapper,
    ): RedisTemplate<String, Any> = RedisTemplate<String, Any>().apply {
        setConnectionFactory(connectionFactory)
        keySerializer   = StringRedisSerializer()
        hashKeySerializer = StringRedisSerializer()
        valueSerializer = GenericJackson2JsonRedisSerializer(snapshotObjectMapper)
        hashValueSerializer = GenericJackson2JsonRedisSerializer(snapshotObjectMapper)
        afterPropertiesSet()
    }
}
