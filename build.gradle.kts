import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.gtnewhorizons.gtnhextlib.build.MinimizeJvmDowngraderApi
import com.gtnewhorizons.gtnhextlib.build.VerifyJvmDowngraderArtifacts
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val jvmDowngraderVersion: String by project
val jvmDowngraderJavaApi8Sha256: String by project
val jvmDowngraderJavaApi17Sha256: String by project
val jvmDowngraderApiChecksums = mapOf(
    "jvmdowngrader-java-api-$jvmDowngraderVersion-downgraded-8.jar" to jvmDowngraderJavaApi8Sha256,
    "jvmdowngrader-java-api-$jvmDowngraderVersion-downgraded-17.jar" to jvmDowngraderJavaApi17Sha256,
)

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

val verifyJvmDowngraderArtifacts by tasks.registering(VerifyJvmDowngraderArtifacts::class) {
    artifacts.from(configurations["bundled"])
    checksums.putAll(jvmDowngraderApiChecksums)
}

tasks.named("fatJar") {
    dependsOn(verifyJvmDowngraderArtifacts)
}
tasks.named("assemble") {
    dependsOn(reobfShadowJar)
}

fun minimizedJvmDowngraderApi(javaTarget: String) =
    tasks.register<MinimizeJvmDowngraderApi>("minimizeJvmDowngraderApi$javaTarget") {
        group = "build"
        description = "Builds the Java $javaTarget JvmDowngrader API required by a closed-pack usage report"
        getJavaTarget().set(javaTarget)
        val jarName = "jvmdowngrader-java-api-$jvmDowngraderVersion-downgraded-$javaTarget.jar"
        expectedSha256.set(jvmDowngraderApiChecksums.getValue(jarName))
        usageReport.set(layout.file(providers.gradleProperty("jvmdgUsageReport$javaTarget").map { file(it) }))
        inputApiJar.set(layout.file(provider {
            configurations["bundled"].single { it.name.endsWith("-downgraded-$javaTarget.jar") }
        }))
        outputJar.set(layout.buildDirectory.file(
            "jvmdg-minimized/java$javaTarget/" +
                "jvmdowngrader-java-api-$jvmDowngraderVersion-downgraded-$javaTarget.jar"))
        auditDirectory.set(layout.buildDirectory.dir("reports/jvmdg-minimized/java$javaTarget"))
    }

val minimizeJvmDowngraderApi8 = minimizedJvmDowngraderApi("8")
val minimizeJvmDowngraderApi17 = minimizedJvmDowngraderApi("17")

tasks.register<Jar>("gtnhOfflineJar") {
    group = "build"
    description =
        "Builds the closed-pack offline jar using -PjvmdgUsageReport8 and -PjvmdgUsageReport17"
    archiveClassifier.set("offline-gtnh")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(tasks.named("reobfJar"), minimizeJvmDowngraderApi8, minimizeJvmDowngraderApi17)

    val reobfJar = tasks.named<ReobfuscatedJar>("reobfJar")
    from(zipTree(reobfJar.flatMap { it.archiveFile })) {
        exclude(
            "META-INF/falsepatternlib_repo/xyz/wagyourtail/jvmdowngrader/" +
                "jvmdowngrader-java-api/**/*.jar")
    }
    manifest {
        from(tasks.shadowJar.get().manifest)
    }

    val apiRepositoryPath =
        "META-INF/falsepatternlib_repo/xyz/wagyourtail/jvmdowngrader/" +
            "jvmdowngrader-java-api/$jvmDowngraderVersion"
    listOf(minimizeJvmDowngraderApi8, minimizeJvmDowngraderApi17).forEach { minimized ->
        into(apiRepositoryPath) {
            from(minimized.flatMap { it.outputJar })
        }
    }
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
