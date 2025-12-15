plugins {
    id("org.springframework.boot") version "3.5.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
}

allprojects {
    group = "com.allfolio"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    // 각 모듈에서 repositories 블록 안 써도 되게 하기 위함
    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
