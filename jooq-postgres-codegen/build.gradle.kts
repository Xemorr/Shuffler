plugins {
    `kotlin-dsl`
}

group = "me.xemor.gradle"
version = "1.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("io.zonky.test:embedded-postgres:2.1.0")
    implementation("org.flywaydb:flyway-core:12.0.3")
    implementation("org.flywaydb:flyway-database-postgresql:12.0.3")
    implementation("org.postgresql:postgresql:42.5.4")
    implementation("org.jooq:jooq-codegen-gradle:3.20.11")
}
