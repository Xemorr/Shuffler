group = "me.xemor"
version = "1.0"
description = "shuffler"
java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version("8.3.6")
    id("io.sentry.jvm.gradle") version("3.12.0")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://repo.codemc.org/repository/maven-public") }
    maven { url = uri("https://repo.minebench.de/") }
    maven { url = uri("https://repo.maven.apache.org/maven2/") }
    maven { url = uri("https://maven.enginehub.org/repo/")}
    maven { url = uri("https://mvn-repo.arim.space/lesser-gpl3")}
    maven { url = uri("https://repo.xemor.zip/releases")}
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
    shadow("io.github.revxrsal:lamp.common:4.0.0-rc.12")
    shadow("io.github.revxrsal:lamp.bukkit:4.0.0-rc.12")
}

java {
    configurations.shadow.get().dependencies.remove(dependencies.gradleApi())
}

tasks.shadowJar {
    minimize()
    configurations = listOf(project.configurations.shadow.get())
    val folder = System.getenv("pluginFolder")
    destinationDirectory.set(file("F:\\Holder\\ShuffleServer\\plugins"))
}

sentry {
    val token = System.getenv("SENTRY_AUTH_TOKEN")
    if (token != null) {
        includeSourceContext.set(true)
        org.set("samuel-hollis-139014fe7")
        projectName.set("superheroes")
        authToken.set(token)
        autoInstallation {
            enabled.set(true)
        }
    }
}

publishing {
    repositories {
        maven {
            name = "xemorReleases"
            url = uri("https://repo.xemor.zip/releases")
            credentials(PasswordCredentials::class)
            authentication {
                isAllowInsecureProtocol = true
                create<BasicAuthentication>("basic")
            }
        }

        maven {
            name = "xemorSnapshots"
            url = uri("https://repo.xemor.zip/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                isAllowInsecureProtocol = true
                create<BasicAuthentication>("basic")
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            groupId = rootProject.group.toString()
            artifactId = rootProject.name
            version = rootProject.version.toString()
            from(project.components["java"])
        }
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}
//End of auto generated

// Handles version variables etc
tasks.processResources {
    inputs.property("version", rootProject.version)
    expand("version" to rootProject.version)
}

publishing {

}
