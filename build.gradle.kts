import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

group = "app.files"
version = providers.gradleProperty("appVersion").getOrElse("1.1.0")
val windowsArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Only Windows x64 and ARM64 are supported")
}

// Lets WindowCorners.kt reach the window handle and call DWM (Windows 11 rounded corners).
val windowJvmOptions = listOf(
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.windows=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)

// fileskmp-version.txt in the jar: the app compares it with the last run's version (Updates.kt).
val versionResource by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast { out.get().file("fileskmp-version.txt").asFile.apply { parentFile.mkdirs(); writeText(v) } }
}

kotlin {
    // Windows-only desktop target (Compose Multiplatform on the JVM).
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            resources.srcDir(versionResource)
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
        jvmArgs += listOf("-Dfile.encoding=UTF-8") + windowJvmOptions
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
        // App icon (exe, shortcuts, MSI). Copied out of the non-ASCII project path first.
        val icon = file("packaging/icon.ico").copyTo(File(work, "icon.ico"), overwrite = true)
        val cmd = listOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "FilesKmp",
            "--app-version", project.version.toString(),
            "--input", input.absolutePath,
            "--main-jar", "FilesKmp.jar",
            "--main-class", "app.files.MainKt",
            "--dest", out.absolutePath,
            "--icon", icon.absolutePath,
            "--add-modules", "java.base,java.desktop,java.logging,jdk.unsupported,jdk.zipfs,java.management,jdk.localedata,java.net.http,jdk.charsets",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dsun.stdout.encoding=UTF-8",
        ) + windowJvmOptions.flatMap { listOf("--java-options", it) }
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
        // jpackage's own main.wxs, patched: (1) a new ProductCode per build and the same
        // version counted as an upgrade, so any build can be installed over the same version;
        // (2) a "Launch FilesKmp" check box on the last page of a fresh install;
        // (3) install into %LOCALAPPDATA%\Programs\FilesKmp (Windows' place for per-user programs),
        // so %LOCALAPPDATA%\FilesKmp holds only the cache. Set when installing, because jpackage's
        // --install-dir "Programs\FilesKmp" fails MSI validation (ICE64) on the extra folder level.
        val resources = File(work, "resources").apply { mkdirs() }
        var wxs = Files.readString(FileSystems.getFileSystem(URI.create("jrt:/"))
            .getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs"))
        fun patch(from: String, to: String) {
            if (!wxs.contains(from)) throw GradleException("jpackage main.wxs changed; cannot find: $from")
            wxs = wxs.replace(from, to)
        }
        patch("Id=\"\$(var.JpProductCode)\"", "Id=\"*\"")
        patch("IncludeMaximum=\"\$(var.JpUpgradeVersionOnlyDetectUpgrade)\"", "IncludeMaximum=\"yes\"")
        patch("<UIRef Id=\"JpUI\"/>", """
            <SetDirectory Id="INSTALLDIR" Value="[LocalAppDataFolder]Programs\FilesKmp\" Sequence="both">NOT Installed</SetDirectory>
            <SetProperty Id="WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT" Value="Launch FilesKmp" After="CostFinalize" Sequence="ui">NOT Installed</SetProperty>
            <Property Id="WIXUI_EXITDIALOGOPTIONALCHECKBOX" Value="1" />
            <Property Id="WixShellExecTarget" Value="[INSTALLDIR]FilesKmp.exe" />
            <CustomAction Id="FkLaunchApp" BinaryKey="WixCA" DllEntry="WixShellExec" Impersonate="yes" />
            <UI>
              <Publish Dialog="ExitDialog" Control="Finish" Event="DoAction" Value="FkLaunchApp">WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 and NOT Installed</Publish>
            </UI>
            <UIRef Id="JpUI"/>""".trimIndent())
        File(resources, "main.wxs").writeText(wxs)
        val cmd = listOf(
            File(System.getProperty("java.home"), "bin/jpackage.exe").absolutePath,
            "--type", "msi", "--name", "FilesKmp", "--app-version", project.version.toString(),
            "--app-image", staged.absolutePath, "--dest", out.absolutePath,
            "--resource-dir", resources.absolutePath,
            "--win-menu", "--win-shortcut", "--win-shortcut-prompt", "--win-dir-chooser", "--win-per-user-install",
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
