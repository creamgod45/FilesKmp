plugins {
    kotlin("multiplatform") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

group = "app.files"
version = "1.0.0"

kotlin {
    // Windows-only desktop target (Compose Multiplatform on the JVM).
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.2")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.files.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
    }
}

// ---------------------------------------------------------------------------
// windowsApp: a stand-alone Windows program in app/build/FilesKmp (FilesKmp.exe
// plus its own trimmed Java runtime). jpackage/jlink read their argument files
// in the system code page, which breaks on this project's non-ASCII folder
// name, so the image is made in an ASCII temp folder and then copied here.
// ---------------------------------------------------------------------------
val windowsApp by tasks.registering {
    group = "distribution"
    description = "Builds build/FilesKmp/FilesKmp.exe (Windows only)."
    dependsOn("packageUberJarForCurrentOS")
    doLast {
        val javaHome = System.getProperty("java.home")
        val jpackage = File(javaHome, "bin/jpackage.exe")
        val work = File(System.getenv("TEMP") ?: "C:\\Temp", "fileskmp-build-${System.currentTimeMillis()}")
        val input = File(work, "input").apply { mkdirs() }
        val out = File(work, "out")
        val jar = layout.buildDirectory.dir("compose/jars").get().asFile
            .listFiles { f -> f.name.endsWith(".jar") }!!.maxByOrNull { it.lastModified() }!!
        jar.copyTo(File(input, "FilesKmp.jar"), overwrite = true)
        val cmd = listOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "FilesKmp",
            "--app-version", "1.0.0",
            "--input", input.absolutePath,
            "--main-jar", "FilesKmp.jar",
            "--main-class", "app.files.MainKt",
            "--dest", out.absolutePath,
            "--add-modules", "java.base,java.desktop,java.logging,jdk.unsupported,jdk.zipfs,java.management,jdk.localedata",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.stdout.encoding=UTF-8",
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw GradleException("jpackage failed:\n$log")
        val dest = layout.buildDirectory.dir("FilesKmp").get().asFile
        dest.deleteRecursively()
        File(out, "FilesKmp").copyRecursively(dest, overwrite = true)
        work.deleteRecursively()
        println("Windows app: ${File(dest, "FilesKmp.exe")}")
    }
}
