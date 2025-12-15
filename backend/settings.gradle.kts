pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "allfolio-backend"

include("user")
include("trade")
include("report")
include("keycloak-spi")