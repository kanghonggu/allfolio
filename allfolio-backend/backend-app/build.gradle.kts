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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
