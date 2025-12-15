plugins {
    `java-library`
}

group = "com.allfolio"
version = "0.0.1-SNAPSHOT"
description = "keycloak-spi"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val keycloakVersion = "26.0.5" // 실제 쓰는 Keycloak 버전에 맞춰서 수정

dependencies {
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")

    // Allfolio User DB에 붙일 때 사용할 라이브러리 (선택)
    // runtimeOnly("org.postgresql:postgresql")
    // implementation("org.springframework:spring-jdbc") 등
}

tasks.withType<Test> {
    useJUnitPlatform()
}
