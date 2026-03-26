group = "me.xemor"
version = "1.4"
description = "shuffler"
java.sourceCompatibility = JavaVersion.VERSION_25
java.targetCompatibility = JavaVersion.VERSION_25

plugins {
    `maven-publish`
    id("com.gradleup.shadow") version("9.3.0")
    id("io.sentry.jvm.gradle") version("3.12.0")
    id("jooq-postgres-codegen")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://repo.codemc.org/repository/maven-public") }
    maven { url = uri("https://repo.minebench.de/") }
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
    maven { url = uri("https://maven.enginehub.org/repo/") }
    maven { url = uri("https://mvn-repo.arim.space/lesser-gpl3") }
    maven { url = uri("https://repo.xemor.zip/releases") }
    maven { url = uri("https://repo.helpch.at/releases/")}
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype-oss-snapshots"
    }
    maven {
        name = "lushpluginsSnapshots"
        url = uri("https://repo.lushplugins.org/snapshots")
    }
    maven {
        name = "william278Releases"
        url = uri("https://repo.william278.net/releases")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    compileOnly("eu.cloudnetservice.cloudnet:driver-api:4.0.0-RC16")
    compileOnly("eu.cloudnetservice.cloudnet:bridge-api:4.0.0-RC16")
    compileOnly("eu.cloudnetservice.cloudnet:wrapper-jvm-api:4.0.0-RC16")
    compileOnly("com.fasterxml.jackson.core:jackson-core:2.18.0")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    compileOnly("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.7.0")
    compileOnly("me.clip:placeholderapi:2.12.2")

    shadow("org.jooq:jooq:3.20.11")
    shadow("org.postgresql:postgresql:42.5.4")
    shadow("com.h2database:h2:2.4.240")
    shadow("org.flywaydb:flyway-core:12.0.3")
    shadow("org.flywaydb:flyway-database-postgresql:12.0.3")
    shadow("io.github.revxrsal:lamp.common:4.0.0-rc.12")
    shadow("io.github.revxrsal:lamp.bukkit:4.0.0-rc.12")
    shadow("com.zaxxer:HikariCP:7.0.2")
    shadow("com.pocketcombats:openskill:1.0")
}

jooq {
    configuration {
        logging = org.jooq.meta.jaxb.Logging.WARN
        generator {
            name = "org.jooq.codegen.JavaGenerator"
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
            }
            target {
                packageName = "me.xemor.shuffler.dest.generated"
                directory = "build/generated-src/jooq"
            }
        }
    }
}

tasks.shadowJar {
    from(sourceSets["migrations"].output)
    mergeServiceFiles {
        include("META-INF/services/**")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    configurations = listOf(project.configurations.shadow.get())
    val folder = System.getenv("pluginFolder")
    if (folder != null) destinationDirectory.set(file(folder))

    relocate("org.jooq", "me.xemor.shuffler.libs.jooq")
    relocate("org.flywaydb", "me.xemor.shuffler.libs.flyway")
    relocate("com.zaxxer.hikari", "me.xemor.shuffler.libs.hikari")
    relocate("org.postgresql", "me.xemor.shuffler.libs.postgresql")
}

java {
    configurations.shadow.get().dependencies.remove(dependencies.gradleApi())
}

tasks.processResources {
    inputs.property("version", rootProject.version)
    expand("version" to rootProject.version)
}
