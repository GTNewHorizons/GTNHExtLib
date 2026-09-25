import com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.gtnewhorizons.gtnhextlib.build.MinimizeJvmDowngraderApi
import com.gtnewhorizons.gtnhextlib.build.VerifyJvmDowngraderArtifacts
import com.gtnewhorizons.gtnhextlib.build.VerifyJvmDowngraderReports
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
tasks.named("check") {
    dependsOn(verifyJvmDowngraderArtifacts)
}
tasks.named("assemble") {
    dependsOn(reobfShadowJar)
}

val report8 = providers.gradleProperty("jvmdgUsageReport8")
val report17 = providers.gradleProperty("jvmdgUsageReport17")
require(report8.isPresent == report17.isPresent) { "Supply both jvmdgUsageReport8 and jvmdgUsageReport17" }
val verifyJvmDowngraderReports by tasks.registering(VerifyJvmDowngraderReports::class) {
    java8Report.set(layout.file(report8.map { file(it) }))
    java17Report.set(layout.file(report17.map { file(it) }))
}

fun minimizedJvmDowngraderApi(javaTarget: String) =
    tasks.register<MinimizeJvmDowngraderApi>("minimizeJvmDowngraderApi$javaTarget") {
        group = "build"
        description = "Builds the Java $javaTarget JvmDowngrader API required by a closed-pack usage report"
        dependsOn(verifyJvmDowngraderReports)
        getJavaTarget().set(javaTarget)
        val jarName = "jvmdowngrader-java-api-$jvmDowngraderVersion-downgraded-$javaTarget.jar"
        expectedSha256.set(jvmDowngraderApiChecksums.getValue(jarName))
        usageReport.set(layout.file(providers.gradleProperty("jvmdgUsageReport$javaTarget").map { file(it) }))
        inputApiJar.set(layout.file(provider {
            configurations["bundled"].single { it.name == jarName }
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
    archiveClassifier.set("gtnh")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.named("reobfJar"), minimizeJvmDowngraderApi8, minimizeJvmDowngraderApi17)

    val version = jvmDowngraderVersion
    val apiJars = mapOf(
        "8" to minimizeJvmDowngraderApi8.flatMap { it.outputJar },
        "17" to minimizeJvmDowngraderApi17.flatMap { it.outputJar },
    )
    val reobfJar = tasks.named<ReobfuscatedJar>("reobfJar")
    from(zipTree(reobfJar.flatMap { it.archiveFile })) {
        exclude("META-INF/MANIFEST.MF")
        exclude(
            "META-INF/falsepatternlib_repo/xyz/wagyourtail/jvmdowngrader/" +
                "jvmdowngrader-java-api/**/*.jar")
        filesMatching(listOf("META-INF/gtnhextlib_deps8.json", "META-INF/gtnhextlib_deps17.json")) {
            filter { line: String ->
                var updated = line
                apiJars.forEach { (target, jar) ->
                    val suffix = MinimizeJvmDowngraderApi.cacheSuffix(jar.get().asFile.toPath())
                    updated = updated.replace(
                        "jvmdowngrader-java-api:$version:downgraded-$target\"",
                        "jvmdowngrader-java-api:$version:downgraded-$target-$suffix\"")
                }
                updated
            }
        }
    }
    manifest {
        from(tasks.shadowJar.get().manifest)
        attributes("GTNH-Minimized-JvmDowngrader-API" to "true")
    }

    val apiRepositoryPath =
        "META-INF/falsepatternlib_repo/xyz/wagyourtail/jvmdowngrader/" +
            "jvmdowngrader-java-api/$jvmDowngraderVersion"
    listOf(minimizeJvmDowngraderApi8, minimizeJvmDowngraderApi17).forEach { minimized ->
        val minimizedJar = minimized.flatMap { it.outputJar }
        into(apiRepositoryPath) {
            from(minimizedJar)
            rename { name ->
                val suffix = MinimizeJvmDowngraderApi.cacheSuffix(minimizedJar.get().asFile.toPath())
                name.removeSuffix(".jar") + "-$suffix.jar"
            }
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
