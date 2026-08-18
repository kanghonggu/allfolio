plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Kafka DLQ
    implementation("org.springframework.kafka:spring-kafka")

    // Prometheus (Micrometer registry)
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OkHttp (WebSocket + REST)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation(project(":common"))
    implementation(project(":portfolio"))
    implementation(project(":asset"))
    implementation(project(":benchmark"))
    implementation(project(":trade"))
    implementation(project(":snapshot"))
    implementation(project(":risk"))
    implementation(project(":esg"))
    implementation(project(":report"))
    implementation(project(":unified-asset"))
    implementation(project(":reconciliation"))
    implementation(project(":workflow"))
    implementation("com.opencsv:opencsv:5.9")
    // 하나은행 고시환율 HTML 파싱 (AF-99) — 공식 API가 없어 화면을 긁는다
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // D1 Task 14 — JpaFeedStoreTest가 실제 ua_assets 네이티브 쿼리(type='STOCK' 필터)를
    // @DataJpaTest로 검증한다. ON CONFLICT/RETURNING(Task 7)과 달리 이 쿼리는 평범한 SELECT라
    // H2로 충분하다 — unified-asset 모듈의 같은 관례(MarketCommodityQuoteJpaRepositoryTest 등)를 따른다.
    testRuntimeOnly("com.h2database:h2")
}
