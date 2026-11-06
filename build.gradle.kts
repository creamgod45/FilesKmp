import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

group = "app.files"
version = providers.gradleProperty("appVersion").getOrElse("1.2.0")
val windowsArch = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Only Windows x64 and ARM64 are supported")
}

// Product information for FilesKmp.exe's version resource, the MSI and Windows' installed-apps list.
// English only: jpackage reads its arguments in the system code page.
object Product {
    const val vendor = "creamgod45"
    const val description = "FilesKmp - file manager for Windows with tabs, split panes, Quick Look and shortcuts"
    const val copyright = "Copyright (c) 2025-2026 creamgod45"
    const val aboutUrl = "https://github.com/creamgod45/FilesKmp"
    const val helpUrl = "https://github.com/creamgod45/FilesKmp/blob/master/docs/USER-GUIDE.en.md"
    const val updateUrl = "https://github.com/creamgod45/FilesKmp/releases"
}
val productArgs = listOf("--vendor", Product.vendor, "--copyright", Product.copyright)

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
                // AI shortcut assistant (AiAgent.kt): Claude Messages API with a fixed tool set.
                implementation("com.anthropic:anthropic-java:3.2.0")
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
// jpackage and WiX. Gradle may run on a runtime without jpackage (IntelliJ runs it on its bundled
// JetBrains Runtime), so jpackage comes from the first full JDK found: -PjdkHome, the JDK running
// Gradle, JAVA_HOME, IntelliJ's downloaded JDKs (~/.jdks), then the usual install folders.
// ---------------------------------------------------------------------------
fun findJpackage(): File {
    val homes = buildList {
        providers.gradleProperty("jdkHome").orNull?.let { add(File(it)) }
        add(File(System.getProperty("java.home")))
        System.getenv("JAVA_HOME")?.let { add(File(it)) }
        File(System.getProperty("user.home"), ".jdks").listFiles()?.sortedByDescending { it.name }?.let { addAll(it) }
        listOfNotNull(System.getenv("ProgramW6432"), System.getenv("ProgramFiles")).distinct().forEach { pf ->
            listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft").forEach { vendor ->
                File(pf, vendor).listFiles()?.sortedByDescending { it.name }?.let { addAll(it) }
            }
        }
    }
    return homes.map { File(it, "bin/jpackage.exe") }.firstOrNull { it.isFile }
        ?: throw GradleException("No JDK with jpackage.exe found (the IDE's bundled runtime has none). Install a JDK 21+ or pass -PjdkHome=<JDK folder>. Looked in:\n" + homes.joinToString("\n"))
}

/** WiX's folder (candle.exe / wix.exe) for jpackage's MSI step: WIX, a portable copy in %LOCALAPPDATA%\wix314, or an installed WiX. */
fun findWix(): File? {
    val dirs = buildList {
        providers.gradleProperty("wixHome").orNull?.let { add(File(it)); add(File(it, "bin")) }
        System.getenv("WIX")?.let { add(File(it)); add(File(it, "bin")) }
        System.getenv("LOCALAPPDATA")?.let { add(File(it, "wix314")) }
        listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles")).forEach { pf ->
            File(pf).listFiles { f -> f.name.startsWith("WiX Toolset") }?.sortedByDescending { it.name }?.forEach { add(File(it, "bin")) }
        }
    }
    return dirs.firstOrNull { File(it, "candle.exe").isFile || File(it, "wix.exe").isFile }
}

/** Runs jpackage with WiX on PATH; fails with its output. */
fun runJpackage(args: List<String>, what: String) {
    val pb = ProcessBuilder(listOf(findJpackage().absolutePath) + args).redirectErrorStream(true)
    findWix()?.let { wix ->
        // Windows usually names it "Path", and Java's map is case-sensitive: adding "PATH" next to it
        // would leave two variables, and jpackage would read the one without WiX.
        val env = pb.environment()
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
        env[key] = wix.absolutePath + File.pathSeparator + (env[key] ?: "")
    }
    val p = pb.start()
    val log = p.inputStream.bufferedReader().readText()
    if (p.waitFor() != 0) throw GradleException("$what failed:\n$log")
}

// ---------------------------------------------------------------------------
// FFmpeg for the shortcut video actions (FlowActionsVideo.kt), shipped as ffmpeg\ffmpeg.exe next to
// FilesKmp.exe: BtbN's static GPL build for this architecture, downloaded once into
// %LOCALAPPDATA%\fileskmp-build\ffmpeg. -PffmpegExe=<ffmpeg.exe> uses a copy of your own,
// -PnoFfmpeg leaves it out (the app then looks for an installed FFmpeg), -PffmpegRelease picks the branch.
// ---------------------------------------------------------------------------
val ffmpegRelease = providers.gradleProperty("ffmpegRelease").getOrElse("n8.1")

/** The folder holding ffmpeg.exe and its licence for the app image, or null when left out. */
fun ffmpegBundle(): File? {
    if (providers.gradleProperty("noFfmpeg").isPresent) return null
    providers.gradleProperty("ffmpegExe").orNull?.let { exe ->
        val f = File(exe); if (!f.isFile) throw GradleException("-PffmpegExe: no such file: $exe")
        return File(System.getenv("TEMP") ?: "C:\\Temp", "fileskmp-ffmpeg-own").apply { deleteRecursively(); mkdirs(); f.copyTo(File(this, "ffmpeg.exe")) }
    }
    val dir = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "fileskmp-build/ffmpeg/$ffmpegRelease-$windowsArch")
    if (File(dir, "ffmpeg.exe").isFile) return dir
    val v = ffmpegRelease.removePrefix("n")
    val name = "ffmpeg-$ffmpegRelease-latest-win${if (windowsArch == "arm64") "arm64" else "64"}-gpl-$v"
    val url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$name.zip"
    println("Downloading FFmpeg: $url")
    val zip = File.createTempFile("ffmpeg-", ".zip")
    try {
        URI(url).toURL().openStream().use { inp -> zip.outputStream().use { inp.copyTo(it) } }
        val tmp = File(dir.parentFile, dir.name + ".part").apply { deleteRecursively(); mkdirs() }
        ZipFile(zip).use { z ->
            fun take(entry: String, to: String) {
                val e = z.getEntry("$name/$entry") ?: throw GradleException("$url has no $entry")
                z.getInputStream(e).use { inp -> File(tmp, to).outputStream().use { inp.copyTo(it) } }
            }
            take("bin/ffmpeg.exe", "ffmpeg.exe")
            take("LICENSE.txt", "LICENSE.txt")
        }
        File(tmp, "README.txt").writeText(
            "ffmpeg.exe is FFmpeg ($ffmpegRelease, static GPL build for Windows $windowsArch by BtbN),\r\n" +
            "used by FilesKmp's shortcut video actions. It is a separate program under the GNU GPL\r\n" +
            "(LICENSE.txt). Build and source: https://github.com/BtbN/FFmpeg-Builds - https://ffmpeg.org\r\n")
        dir.deleteRecursively(); tmp.renameTo(dir)
    } finally { zip.delete() }
    return dir
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
        val work = File(System.getenv("TEMP") ?: "C:\\Temp", "fileskmp-build-${System.currentTimeMillis()}")
        val input = File(work, "input").apply { mkdirs() }
        val out = File(work, "out")
        val jar = layout.buildDirectory.dir("compose/jars").get().asFile
            .listFiles { f -> f.name.endsWith(".jar") }!!.maxByOrNull { it.lastModified() }!!
        jar.copyTo(File(input, "FilesKmp.jar"), overwrite = true)
        // App icon (exe, shortcuts, MSI). Copied out of the non-ASCII project path first.
        val icon = file("packaging/icon.ico").copyTo(File(work, "icon.ico"), overwrite = true)
        val cmd = listOf(
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
            // The exe's description is what Task Manager lists the process as: keep it the name.
            "--description", "FilesKmp",
        ) + productArgs + windowJvmOptions.flatMap { listOf("--java-options", it) }
        runJpackage(cmd, "jpackage")
        val dest = layout.buildDirectory.dir("FilesKmp").get().asFile
        dest.deleteRecursively()
        File(out, "FilesKmp").copyRecursively(dest, overwrite = true)
        work.deleteRecursively()
        ffmpegBundle()?.let { ff ->
            ff.copyRecursively(File(dest, "ffmpeg"), overwrite = true)
            println("FFmpeg bundled: ${File(dest, "ffmpeg\\ffmpeg.exe")}")
        } ?: println("FFmpeg not bundled (-PnoFfmpeg)")
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
        // The template of the JDK whose jpackage runs (not necessarily the runtime Gradle runs on).
        val jdkHome = findJpackage().parentFile.parentFile
        var wxs = FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to jdkHome.absolutePath)).use { fs ->
            Files.readString(fs.getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs"))
        }
        fun patch(from: String, to: String) {
            if (!wxs.contains(from)) throw GradleException("jpackage main.wxs changed; cannot find: $from")
            wxs = wxs.replace(from, to)
        }
        patch("Id=\"\$(var.JpProductCode)\"", "Id=\"*\"")
        patch("IncludeMaximum=\"\$(var.JpUpgradeVersionOnlyDetectUpgrade)\"", "IncludeMaximum=\"yes\"")
        // (4) the wizard's pictures (packaging/make-msi-art.ps1 draws them from icon.ico).
        val banner = file("packaging/msi-banner.bmp").copyTo(File(work, "msi-banner.bmp"))
        val dialog = file("packaging/msi-dialog.bmp").copyTo(File(work, "msi-dialog.bmp"))
        patch("<UIRef Id=\"JpUI\"/>", """
            <WixVariable Id="WixUIBannerBmp" Value="${banner.absolutePath}" />
            <WixVariable Id="WixUIDialogBmp" Value="${dialog.absolutePath}" />
            <UIRef Id="JpUI"/>""".trimIndent())
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
            "--type", "msi", "--name", "FilesKmp", "--app-version", project.version.toString(),
            "--app-image", staged.absolutePath, "--dest", out.absolutePath,
            "--resource-dir", resources.absolutePath,
            "--win-menu", "--win-shortcut", "--win-shortcut-prompt", "--win-dir-chooser", "--win-per-user-install",
            "--win-upgrade-uuid", if (windowsArch == "arm64") "a816d2b1-9605-43ab-b441-70ae0e83e548" else "1ab430d5-5393-48a5-9bbb-5f98bf171a14",
            // Installed-apps entry: icon, publisher, support links.
            "--icon", file("packaging/icon.ico").copyTo(File(work, "icon.ico")).absolutePath,
            "--about-url", Product.aboutUrl, "--win-help-url", Product.helpUrl, "--win-update-url", Product.updateUrl,
            "--description", Product.description,
        ) + productArgs
        runJpackage(cmd, "MSI packaging")
        val msi = out.listFiles()?.singleOrNull { it.extension == "msi" }
            ?: throw GradleException("jpackage did not produce one MSI in $out")
        val dest = layout.buildDirectory.dir("installers").get().asFile.apply { mkdirs() }
        msi.copyTo(File(dest, "FilesKmp-${project.version}-windows-$windowsArch.msi"), overwrite = true)
        work.deleteRecursively()
    }
}
