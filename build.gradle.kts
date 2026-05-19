import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.processResources {
    from(configurations["deploader"]) {
        rename { "fplib_deploader.jar" }
    }
}

tasks.shadowJar {
    archiveClassifier.set("slim-dev")
    manifest {
        attributes("Implementation-Version" to (System.getenv("RELEASE_VERSION") ?: project.version.toString()))
    }
}

@Suppress("UNCHECKED_CAST")
afterEvaluate {
    val allDeps = project.ext.get("depload_libs") as List<Pair<String, String>>
    tasks.processResources {
        allDeps.groupBy { it.second }
            .mapValues { it.value.map { p -> p.first } }
            .forEach { (java, deps) ->
                filesMatching("META-INF/gtnhextlib_deps${java}.json") {
                    expand("DEPS" to deps.joinToString("\", \""))
                }
            }
    }
    val bundled = configurations["bundled"]
    tasks.named<Jar>("fatJar").configure {
        allDeps.forEach { (coord, _) ->
            val parts = coord.split(':')
            val path = "META-INF/falsepatternlib_repo/${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/"
            val jarName = parts.drop(1).joinToString("-") + ".jar"
            into(path) {
                from(bundled.filter { f -> f.name == jarName })
            }
        }
    }
}

val fatJar by tasks.registering(Jar::class) {
    group = "build"
    archiveClassifier.set("dev")
    val shadow = tasks.named<ShadowJar>("shadowJar")
    dependsOn(shadow)
    from(zipTree(shadow.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        from(shadow.get().manifest)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<ReobfuscatedJar>("reobfJar").configure {
    setInputJarFromTask(fatJar)
}

val reobfShadowJar = tasks.named<ReobfuscatedJar>("reobfShadowJar")
reobfShadowJar.configure {
    archiveClassifier.set("slim")
}
tasks.named("assemble") {
    dependsOn(reobfShadowJar)
}

// Only publish fat jar to mn/cf
extra["publishableDevJar"] = fatJar

tasks.sourcesJar {
    val bundledSources = configurations["bundledSources"]
    dependsOn(bundledSources)
    from(provider {
        bundledSources.files.map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/versions/9/module-info.*")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = (System.getenv("ARTIFACT_GROUP_ID") ?: project.group.toString())
            artifactId = (System.getenv("ARTIFACT_ID") ?: "GTNHExtLib")
            afterEvaluate {
                version = System.getenv("RELEASE_VERSION") ?: project.version.toString()
            }
            artifact(tasks.named<ReobfuscatedJar>("reobfJar"))
            artifact(fatJar.get())
            artifact(reobfShadowJar.get())
            artifact(tasks.shadowJar.get())
            artifact(tasks.named("sourcesJar")) { classifier = "sources" }
        }
    }
    repositories {
        val mavenUser = System.getenv("MAVEN_USER")
        val mavenPass = System.getenv("MAVEN_PASSWORD")
        if (mavenUser != null) {
            maven {
                name = "main"
                url = uri(System.getenv("MAVEN_PUBLISH_URL") ?: "https://nexus.gtnewhorizons.com/repository/releases/")
                credentials {
                    username = mavenUser
                    password = mavenPass
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
}
