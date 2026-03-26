import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import java.lang.reflect.Modifier
import java.net.URLClassLoader

plugins {
    java
    id("org.jooq.jooq-codegen-gradle")
}

sourceSets {
    create("migrations") {
        java.srcDirs("src/migrations")
    }
}

dependencies {
    "migrationsImplementation"("org.flywaydb:flyway-core:12.0.3")
    "migrationsImplementation"("org.jooq:jooq:3.20.11")
    "migrationsImplementation"("org.postgresql:postgresql:42.5.4")
    "jooqCodegen"(sourceSets["migrations"].output)
    "jooqCodegen"("org.postgresql:postgresql:42.5.4")
    "implementation"(sourceSets["migrations"].output)
}

sourceSets.named("main") {
    java.srcDir("build/generated-src/jooq")
}

var embeddedPostgres: EmbeddedPostgres? = null

val startPostgres = tasks.register("startPostgres") {
    dependsOn(tasks.named("migrationsClasses"))
    doLast {
        val pg = EmbeddedPostgres.builder().start()
        embeddedPostgres = pg
        val dbUrl = pg.getJdbcUrl("postgres", "postgres")

        // 1. Build the ClassLoader from the migration output folders
        val migrationUrls = sourceSets["migrations"].output.classesDirs.files.map { it.toURI().toURL() }.toTypedArray()
        val customLoader = URLClassLoader(migrationUrls, Flyway::class.java.classLoader)

        // 2. MANUAL SCAN: Find all .class files in the output directory
        val migrationInstances = mutableListOf<org.flywaydb.core.api.migration.JavaMigration>()
        val migrationOutput = sourceSets["migrations"].output.classesDirs.files
        migrationOutput.forEach { rootDir ->
            rootDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
                // Convert file path to binary class name
                // e.g. /path/to/me/xemor/V1.class -> me.xemor.V1
                val relativePath = classFile.relativeTo(rootDir).path
                val className = relativePath.removeSuffix(".class").replace(File.separatorChar, '.')

                try {
                    val clazz = customLoader.loadClass(className)
                    // Only pick up classes that actually implement JavaMigration and aren't abstract
                    if (org.flywaydb.core.api.migration.JavaMigration::class.java.isAssignableFrom(clazz) &&
                        !Modifier.isAbstract(clazz.modifiers)) {

                        val instance = clazz.getDeclaredConstructor().newInstance() as org.flywaydb.core.api.migration.JavaMigration
                        migrationInstances.add(instance)
                        println("Manual Scan found and loaded: $className")
                    }
                } catch (e: Exception) {
                    println("Skipping $className: ${e.message}")
                }
            }
        }

        // 3. Hand the instance directly to Flyway
        Flyway.configure(customLoader)
            .dataSource(dbUrl, "postgres", "postgres")
            .javaMigrations(*migrationInstances.toTypedArray())
            .load()
            .migrate()

        jooq {
            configuration {
                jdbc {
                    url = dbUrl
                    user = "postgres"
                    password = "postgres"
                }
            }
        }
    }
}

val stopPostgres = tasks.register("stopPostgres") {
    doLast {
        embeddedPostgres?.close()
    }
}

tasks.named("jooqCodegen") {
    dependsOn(startPostgres)
    finalizedBy(stopPostgres)
}

tasks.named("compileJava") {
    dependsOn(tasks.named("jooqCodegen"))
}
