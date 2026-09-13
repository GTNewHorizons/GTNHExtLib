import java.security.MessageDigest
import java.util.Properties

plugins {
    java
}

val jvmDowngraderProperties = providers
    .fileContents(layout.projectDirectory.file("../gradle.properties"))
    .asText
    .map { text: String -> Properties().apply { load(text.reader()) } }
val jvmDowngraderVersion = jvmDowngraderProperties.map { it.getProperty("jvmDowngraderVersion") }
val jvmDowngraderSha256 = jvmDowngraderProperties.map { it.getProperty("jvmDowngraderSha256") }

repositories {
    mavenCentral()
    maven("https://maven.wagyourtail.xyz/releases/")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("xyz.wagyourtail.jvmdowngrader:jvmdowngrader:${jvmDowngraderVersion.get()}")
}

fun verifyJvmDowngrader(file: File) {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual.equals(jvmDowngraderSha256.get(), ignoreCase = true)) {
        "SHA-256 mismatch for ${file.name}: expected ${jvmDowngraderSha256.get()}, got $actual"
    }
}

configurations.matching { it.name == "compileClasspath" || it.name == "runtimeClasspath" }.configureEach {
    incoming.afterResolve {
        val jarName = "jvmdowngrader-${jvmDowngraderVersion.get()}.jar"
        verifyJvmDowngrader(files.single { it.name == jarName })
    }
}
