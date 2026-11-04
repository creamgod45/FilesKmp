import java.nio.file.Files

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

group = "app.files"
version = providers.gradleProperty("appVersion").getOrElse("1.0.0")
val windowsArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Only Windows x64 and ARM64 are supported")
}

kotlin {
    // Windows-only desktop target (Compose Multiplatform on the JVM).
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-$windowsArch:1.12.2")
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
            "--app-version", project.version.toString(),
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

// Build an MSI from the same launcher/runtime used by the portable ZIP.
val windowsMsi by tasks.registering {
    group = "distribution"
    dependsOn(windowsApp)
    doLast {
        val work = Files.createTempDirectory("fileskmp-msi-").toFile()
        val staged = File(work, "FilesKmp")
        layout.buildDirectory.dir("FilesKmp").get().asFile.copyRecursively(staged)
        val out = File(work, "out")
        val cmd = listOf(
            File(System.getProperty("java.home"), "bin/jpackage.exe").absolutePath,
            "--type", "msi", "--name", "FilesKmp", "--app-version", project.version.toString(),
            "--app-image", staged.absolutePath, "--dest", out.absolutePath,
            "--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
            "--win-upgrade-uuid", if (windowsArch == "arm64") "a816d2b1-9605-43ab-b441-70ae0e83e548" else "1ab430d5-5393-48a5-9bbb-5f98bf171a14",
        )
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw GradleException("MSI packaging failed: $log")
        val msi = out.listFiles()?.singleOrNull { it.extension == "msi" }
            ?: throw GradleException("jpackage did not produce one MSI: $log")
        val dest = layout.buildDirectory.dir("installers").get().asFile.apply { mkdirs() }
        msi.copyTo(File(dest, "FilesKmp-${project.version}-windows-$windowsArch.msi"), overwrite = true)
        work.deleteRecursively()
    }
}
