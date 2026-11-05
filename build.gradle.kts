@file:Suppress("SpellCheckingInspection")

import org.gradle.internal.os.OperatingSystem
import java.security.MessageDigest

plugins {
    java
    application
}

group = "com.retro.engine"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val lwjglVersion = "3.4.3"

val lwjglNatives = listOf(
    "natives-windows",
    "natives-linux",
    "natives-linux-arm64",
    "natives-macos",
    "natives-macos-arm64"
)

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))

    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-sdl") // Gamepad rumble (GLFW has no haptics API)
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.lwjgl:lwjgl-assimp")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-tinyfd")
    implementation("org.lwjgl:lwjgl-openal") // 3D positional audio (bundles OpenAL Soft)

    // In-Memory Janino Scripting Compiler
    implementation("org.codehaus.janino:janino:3.1.12")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    for (platformNative in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-sdl::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-assimp::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-stb::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-tinyfd::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-openal::$platformNative")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val hostOs: OperatingSystem = OperatingSystem.current()

/**
 * JVM flags shared by `gradle run` and the packaged editor. [moltenVkPath] is where libMoltenVK.dylib lives on macOS;
 * -XstartOnFirstThread is macOS-only (GLFW/Cocoa need the main thread) and is an invalid flag elsewhere.
 */
fun engineJvmArgs(moltenVkPath: String): List<String> = buildList {
    add("-Xms256m")
    add("-Xmx512m")
    add("-Dorg.lwjgl.system.stack.size=128") // Boosts LWJGL thread stack capacity
    add("--enable-native-access=ALL-UNNAMED")
    if (hostOs.isMacOsX) {
        add("-XstartOnFirstThread")
        add("-Dorg.lwjgl.vulkan.libname=$moltenVkPath")
    }
}

application {
    mainClass.set("com.retro.engine.Main")
    applicationDefaultJvmArgs = engineJvmArgs("${project.projectDir}/libs/macos/libMoltenVK.dylib")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Tests run on the shipped heap size, with no window or GPU, and with a throwaway home folder so nothing touches the
// developer's real ~/.retroengine. Timing-sensitive tests are tagged "perf": shared CI machines are too noisy for them.
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("perf") }
}

tasks.register<Test>("perfTest") {
    group = "verification"
    description = "Runs the timing-sensitive scaling tests. Use a quiet machine; not part of CI."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    outputs.upToDateWhen { false }
}

fun findGlslcExecutable(): String? {
    val vulkanSdk = System.getenv("VULKAN_SDK")
    if (!vulkanSdk.isNullOrEmpty()) {
        val sdkGlslcWin = file("$vulkanSdk/bin/glslc.exe")
        if (sdkGlslcWin.exists()) return sdkGlslcWin.absolutePath
        val sdkGlslc = file("$vulkanSdk/bin/glslc")
        if (sdkGlslc.exists()) return sdkGlslc.absolutePath
    }

    val vulkanBaseDir = file("C:/VulkanSDK")
    if (vulkanBaseDir.exists()) {
        val subDirs = vulkanBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in subDirs) {
            val candidate = file("${dir.absolutePath}/Bin/glslc.exe")
            if (candidate.exists()) return candidate.absolutePath
        }
    }

    val commonPaths = listOf(
        "/opt/homebrew/bin/glslc",
        "/usr/local/bin/glslc",
        "/usr/bin/glslc"
    )
    for (path in commonPaths) {
        val f = file(path)
        if (f.exists()) return f.absolutePath
    }

    for (cmdName in listOf("glslc.exe", "glslc")) {
        try {
            val testProc = ProcessBuilder(cmdName, "--version").start()
            if (testProc.waitFor() == 0) return cmdName
        } catch (e: Exception) {
            // continue
        }
    }

    return null
}

tasks.register("compileVulkanShaders") {
    group = "build"
    description = "Compiles GLSL shaders into SPIR-V binaries using glslc."

    doLast {
        val shaderDir = file("src/main/resources/shaders/vulkan")
        if (!shaderDir.exists()) return@doLast

        val shaderFiles = shaderDir.listFiles()?.filter { it.extension in listOf("vert", "frag") } ?: emptyList()
        if (shaderFiles.isEmpty()) return@doLast

        val glslcExec = findGlslcExecutable()

        if (glslcExec == null) {
            println("==========================================================================")
            println("[WARNING] glslc executable not found on system PATH or Vulkan SDK location.")
            println("Shader compilation skipped.")
            println("==========================================================================")

            val missingSpv = shaderFiles.filter { !file("${it.absolutePath}.spv").exists() }
            if (missingSpv.isNotEmpty()) {
                throw GradleException("Missing pre-compiled .spv files for: ${missingSpv.map { it.name }}.")
            }
            return@doLast
        }

        shaderFiles.forEach { shader ->
            val outputFile = file("${shader.absolutePath}.spv")
            val exitCode = ProcessBuilder(glslcExec, shader.absolutePath, "-o", outputFile.absolutePath)
                .inheritIO()
                .start()
                .waitFor()

            if (exitCode != 0) {
                throw GradleException("Failed to compile ${shader.name} with $glslcExec (exit code: $exitCode)")
            }
        }
    }
}

val metalShaderDir = file("src/main/resources/shaders/metal")
val metalLibOutputDir = layout.buildDirectory.dir("generated/metal")

fun metalToolchainAvailable(): Boolean = try {
    val proc = ProcessBuilder("xcrun", "-sdk", "macosx", "metal", "--version").redirectErrorStream(true).start()
    proc.inputStream.readAllBytes()
    proc.waitFor() == 0
} catch (e: Exception) {
    false
}

val compileMetalShaders = tasks.register("compileMetalShaders") {
    group = "build"
    description = "Precompiles Metal shaders into .metallib archives with xcrun (macOS only)."

    inputs.files(fileTree(metalShaderDir) { include("*.metal") })
    outputs.dir(metalLibOutputDir)

    onlyIf("host OS is macOS") { OperatingSystem.current().isMacOsX }
    onlyIf("Metal toolchain is installed") { metalToolchainAvailable() }

    val shaderDir = metalShaderDir
    val outputRoot = metalLibOutputDir
    doLast {
        val outDir = outputRoot.get().dir("shaders/metal").asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val airDir = temporaryDir

        shaderDir.listFiles { f -> f.extension == "metal" }?.sortedBy { it.name }?.forEach { shader ->
            val air = File(airDir, "${shader.nameWithoutExtension}.air")
            val metallib = File(outDir, "${shader.nameWithoutExtension}.metallib")
            val steps = listOf(
                listOf("xcrun", "-sdk", "macosx", "metal", "-std=metal3.0", "-mmacosx-version-min=13.0",
                       "-c", shader.absolutePath, "-o", air.absolutePath),
                listOf("xcrun", "-sdk", "macosx", "metallib", air.absolutePath, "-o", metallib.absolutePath)
            )
            for (cmd in steps) {
                val exitCode = ProcessBuilder(cmd).inheritIO().start().waitFor()
                if (exitCode != 0) {
                    throw GradleException("Failed to compile ${shader.name}")
                }
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(shader.readBytes()).joinToString("") { "%02x".format(it) }
            File(outDir, "${metallib.name}.sha256").writeText(sha)
        }
    }
}

sourceSets.named("main") {
    resources.srcDir(compileMetalShaders)
}

tasks.register("compileShaders") {
    group = "build"
    description = "Compiles all shader backends."
    dependsOn("compileVulkanShaders", compileMetalShaders)
}

tasks.named("processResources") {
    dependsOn("compileShaders")
}

tasks.register<JavaExec>("cookAssets") {
    group = "build"
    description = "Automatically bakes raw Assets/ into binary .rpack packages using RpackCookerCli."
    dependsOn(tasks.named("classes"))

    mainClass.set("com.retro.engine.assets.RpackCookerCli")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--dir", "Assets", "--out", "Cooked")
}

tasks.named("run") {
    dependsOn("cookAssets")
}

// ------------------------------------------------------------------------------------------
// Standalone Editor packaging (jpackage), one build per host OS/arch:
//   ./gradlew packageEditor                         -> build/dist/RetroEngine(.app)
//   ./gradlew packageEditor -PinstallerType=dmg     -> dmg | pkg | msi | exe | deb | rpm
//   ./gradlew packageEditor -PwinConsole            -> Windows launcher with a console window
//   ./gradlew packageEditor -PappName="My Game" -PappIcon=path/to/icon.icns
//                                                   -> custom jpackage --name / --icon (.icns macOS, .ico Windows, .png Linux)
//
// macOS distribution signing (default is jpackage's ad-hoc signature, which Gatekeeper blocks on other Macs):
//   ./gradlew packageEditor  -PmacSignIdentity="Developer ID Application: Name (TEAMID)"
//   ./gradlew notarizeEditor -PmacSignIdentity="..." [-PinstallerType=dmg] [-PnotaryProfile=RetroEngine-notary]
// notarizeEditor packages, submits to Apple's notary service, staples the ticket and runs a Gatekeeper check.
// One-time setup: xcrun notarytool store-credentials RetroEngine-notary --apple-id <id> --team-id <TEAMID>
// pkg installers additionally need -PmacInstallerSignIdentity="Developer ID Installer: Name (TEAMID)".
//
// Layout of the app payload ($APPDIR):
//   retro-engine.jar + dependency jars     host-OS natives only (both archs; LWJGL picks at load time),
//                                           so the packaged editor exports games for its own OS only
//   natives/libMoltenVK.dylib              macOS only (universal arm64 + x86_64)
//   game-runtime/                          jlinked JRE that games exported from the editor embed when
//                                           no full JDK is installed on the user's machine
//   game-launcher/                         Windows only: jpackage's launcher .exe, which those games are built from
// ------------------------------------------------------------------------------------------

val editorAppName: String = providers.gradleProperty("appName").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "RetroEngine"
val editorAppIcon: File? = providers.gradleProperty("appIcon").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { file(it) }
    ?: file("src/main/resources/icons/icon." + when {
        hostOs.isMacOsX -> "icns"
        hostOs.isWindows -> "ico"
        else -> "png"
    }).takeIf { it.isFile } // default: OS-appropriate icon from src/main/resources/icons/
val editorAppVersion = version.toString().substringBefore("-") // jpackage rejects "-SNAPSHOT"

// Modules for the editor itself (jdeps: java.base,java.desktop,java.logging,jdk.unsupported, plus
// jdk.zipfs which jdeps cannot see). java.desktop carries javax.sound (AudioSource / UISoundPlayer).
// Keep in sync with StandaloneExporter.RUNTIME_MODULES.
val engineRuntimeModules = "java.base,java.desktop,java.logging,jdk.unsupported,jdk.zipfs"

val toolchainHome: Provider<File> = javaToolchains
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .map { it.metadata.installationPath.asFile }

fun jdkTool(home: File, name: String): String =
    File(home, "bin/$name" + if (hostOs.isWindows) ".exe" else "").absolutePath

val editorBuildDir = layout.buildDirectory.dir("editor")
val editorInputDir = layout.buildDirectory.dir("editor/input")
val distDir = layout.buildDirectory.dir("dist")

fun makeOwnerWritable(root: File) = root.walk().forEach { it.setWritable(true, true) }

val macSignIdentity: Provider<String> = providers.gradleProperty("macSignIdentity")
val macInstallerSignIdentity: Provider<String> = providers.gradleProperty("macInstallerSignIdentity")
val macEntitlements: File = layout.projectDirectory.file("packaging/macos/entitlements.plist").asFile

fun runChecked(cmd: List<String>, what: String, dir: File? = null) {
    val code = ProcessBuilder(cmd).directory(dir).inheritIO().start().waitFor()
    if (code != 0) throw GradleException("$what failed (exit $code): ${cmd.joinToString(" ")}")
}

/** Developer ID signature with hardened runtime + secure timestamp, both required for notarization. */
fun codesignForDistribution(identity: String, file: File) = runChecked(
    listOf("codesign", "--force", "--timestamp", "--options", "runtime", "--sign", identity, file.absolutePath),
    "codesign"
)

/**
 * The notary service inspects dylibs inside jars, and LWJGL ships them unsigned (x64) or ad-hoc (arm64).
 * Re-signs each one in place. LWJGL verifies every loaded native against a META-INF/.../<lib>.sha1 that exists in
 * both the natives jar and its classes jar (lwjgl-glfw-natives-macos.jar -> lwjgl-glfw.jar), so both get refreshed.
 */
fun signNativesInJar(jarTool: String, jar: File, identity: String, scratch: File) {
    scratch.deleteRecursively()
    scratch.mkdirs()
    runChecked(listOf(jarTool, "xf", jar.absolutePath), "jar extract", scratch) // no project.copy: config-cache safe
    val dylibs = scratch.walk().filter { it.isFile && it.name.endsWith(".dylib") }.toList()
    if (dylibs.isEmpty()) return
    val updated = mutableListOf<String>()
    for (lib in dylibs) {
        codesignForDistribution(identity, lib)
        updated += lib.relativeTo(scratch).invariantSeparatorsPath
        val sha1 = File(scratch, "META-INF/" + lib.relativeTo(scratch).invariantSeparatorsPath + ".sha1")
        if (sha1.isFile) {
            val hex = MessageDigest.getInstance("SHA-1").digest(lib.readBytes()).joinToString("") { "%02x".format(it) }
            sha1.writeText(hex + "\n")
            updated += sha1.relativeTo(scratch).invariantSeparatorsPath
        }
    }
    runChecked(listOf(jarTool, "uf", jar.absolutePath) + updated.flatMap { listOf("-C", scratch.absolutePath, it) }, "jar update")

    val shaFiles = updated.filter { it.endsWith(".sha1") }
    val classesJar = File(jar.parentFile, jar.name.replace(Regex("-natives-macos(-arm64)?"), ""))
    if (shaFiles.isNotEmpty()) {
        if (!classesJar.isFile) throw GradleException("No classes jar ${classesJar.name} for ${jar.name} (LWJGL hash check would fail)")
        runChecked(listOf(jarTool, "uf", classesJar.absolutePath) + shaFiles.flatMap { listOf("-C", scratch.absolutePath, it) }, "jar update")
    }
}

val linkGameRuntime = tasks.register("linkGameRuntime") {
    group = "distribution"
    description = "jlinks the JRE that games exported from the packaged editor embed."
    val out = editorBuildDir.map { it.dir("game-runtime") }
    val jdk = toolchainHome
    inputs.property("modules", engineRuntimeModules)
    outputs.dir(out)
    doLast {
        val outDir = out.get().asFile
        outDir.deleteRecursively() // jlink refuses an existing output dir
        // bin/java(w) is kept: exported games launch it from a script when jpackage is unavailable
        val code = ProcessBuilder(
            jdkTool(jdk.get(), "jlink"),
            "--add-modules", engineRuntimeModules,
            "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", outDir.absolutePath
        ).inheritIO().start().waitFor()
        if (code != 0) throw GradleException("jlink failed (exit $code)")
        makeOwnerWritable(outDir) // jlink emits read-only files, which later copies/syncs cannot overwrite
    }
}

// jpackage can't be jlinked into the editor (JDK 24+ without jmods can't link jdk.jlink), so a Windows editor on a
// machine with no JDK would have no way to make an .exe. Its prebuilt launcher is all that step needs.
val windowsGameLauncher = "jpackageapplauncherw.exe"
val extractGameLauncher = tasks.register("extractGameLauncher") {
    group = "distribution"
    description = "Copies jpackage's Windows launcher out of the toolchain JDK for exported games."
    val out = editorBuildDir.map { it.dir("game-launcher") }
    val jdk = toolchainHome
    val launcher = windowsGameLauncher
    outputs.dir(out)
    doLast {
        val outDir = out.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val home = jdk.get()
        runChecked(listOf(jdkTool(home, "jimage"), "extract", "--include", "regex:.*/$launcher",
                          "--dir", outDir.absolutePath, File(home, "lib/modules").absolutePath), "jimage extract")
        val moduleDir = File(outDir, "jdk.jpackage")
        val extracted = moduleDir.walk().firstOrNull { it.name == launcher }
            ?: throw GradleException("$launcher not found in the JDK at $home")
        extracted.copyTo(File(outDir, launcher))
        moduleDir.deleteRecursively()
    }
}

val stageEditor = tasks.register<Sync>("stageEditor") {
    group = "distribution"
    description = "Stages the jars, natives and embedded game runtime for jpackage."
    into(editorInputDir)
    doFirst { makeOwnerWritable(editorInputDir.get().asFile) } // stale read-only jlink files from older builds
    from(tasks.named("jar")) { rename { "retro-engine.jar" } } // the name StandaloneExporter expects
    from(configurations.runtimeClasspath) {
        // e.g. on macOS keep "-natives-macos" and "-natives-macos-arm64", drop Windows/Linux natives
        val hostNatives = "-natives-" + when {
            hostOs.isMacOsX -> "macos"
            hostOs.isWindows -> "windows"
            else -> "linux"
        }
        exclude { it.name.contains("-natives-") && !it.name.contains(hostNatives) }
    }
    if (hostOs.isMacOsX) {
        from("libs/macos/libMoltenVK.dylib") { into("natives") }
    }
    from(linkGameRuntime) { into("game-runtime") }
    if (hostOs.isWindows) {
        from(extractGameLauncher) { into("game-launcher") }
    }

    if (hostOs.isMacOsX) {
        val identity = macSignIdentity
        val jdk = toolchainHome
        val scratchRoot = editorBuildDir.map { it.dir("native-sign-tmp") }
        inputs.property("macSignIdentity", identity.orElse(""))
        doLast {
            if (!identity.isPresent) return@doLast
            val jarTool = jdkTool(jdk.get(), "jar")
            destinationDir.listFiles { f -> f.name.contains("-natives-macos") && f.name.endsWith(".jar") }!!.forEach {
                signNativesInJar(jarTool, it, identity.get(), scratchRoot.get().dir(it.nameWithoutExtension).asFile)
            }
        }
    }
}

tasks.register("packageEditor") {
    group = "distribution"
    description = "Packages the full RetroEngine editor as a native app for the host OS (jpackage)."
    dependsOn(stageEditor)

    val installerType = providers.gradleProperty("installerType").orElse("app-image")
    val winConsole = providers.gradleProperty("winConsole").isPresent
    val jdk = toolchainHome
    val input = editorInputDir
    val dest = distDir
    val buildRoot = editorBuildDir
    val signIdentity = macSignIdentity
    val installerIdentity = macInstallerSignIdentity
    val entitlements = macEntitlements
    // Declaring any input enables up-to-date checks, so every input must be listed or stale apps get reused
    inputs.dir(input)
    inputs.file(entitlements)
    inputs.property("macSignIdentity", signIdentity.orElse(""))
    inputs.property("installerType", installerType)
    inputs.property("winConsole", winConsole)
    inputs.property("appName", editorAppName)
    val appIcon = editorAppIcon
    if (appIcon != null) inputs.file(appIcon)
    outputs.dir(dest)

    doLast {
        val type = installerType.get()
        val destDir = dest.get().asFile
        // jpackage fails if its output already exists
        destDir.listFiles { f -> f.name.startsWith(editorAppName) }?.forEach { it.deleteRecursively() }
        destDir.mkdirs()

        val cmd = mutableListOf(
            jdkTool(jdk.get(), "jpackage"),
            "--type", type,
            "--name", editorAppName,
            "--app-version", editorAppVersion,
            "--vendor", "RetroEngine",
            "--input", input.get().asFile.absolutePath,
            "--main-jar", "retro-engine.jar",
            "--main-class", "com.retro.engine.Main",
            "--dest", destDir.absolutePath,
            "--temp", buildRoot.get().dir("jpackage-tmp").asFile.apply { deleteRecursively() }.absolutePath,
            "--add-modules", engineRuntimeModules,
            "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands"
        )
        if (appIcon != null) {
            val expected = when { hostOs.isMacOsX -> "icns"; hostOs.isWindows -> "ico"; else -> "png" }
            if (!appIcon.isFile) throw GradleException("-PappIcon not found: $appIcon")
            if (!appIcon.name.lowercase().endsWith(".$expected")) throw GradleException("-PappIcon must be a .$expected file on this OS: $appIcon")
            cmd += listOf("--icon", appIcon.absolutePath)
        }
        val javaOptions = engineJvmArgs("\$APPDIR/natives/libMoltenVK.dylib") + listOf(
            "-Dretro.packaged=true",
            "-Dretro.gameRuntime=\$APPDIR/game-runtime"
        )
        javaOptions.forEach { cmd += listOf("--java-options", it) }
        if (hostOs.isWindows) cmd += listOf("--java-options", "-Dretro.gameLauncher=\$APPDIR/game-launcher/$windowsGameLauncher")

        when {
            hostOs.isMacOsX -> {
                cmd += listOf("--mac-package-identifier", "com.retro.engine.editor",
                              "--mac-package-name", editorAppName)
                if (signIdentity.isPresent) {
                    // jpackage signs every executable/dylib in the bundle (incl. game-runtime) with these entitlements
                    cmd += listOf("--mac-sign", "--mac-app-image-sign-identity", signIdentity.get(),
                                  "--mac-entitlements", entitlements.absolutePath)
                    if (type == "pkg") {
                        if (!installerIdentity.isPresent) throw GradleException(
                            "pkg signing needs -PmacInstallerSignIdentity=\"Developer ID Installer: ...\"")
                        cmd += listOf("--mac-installer-sign-identity", installerIdentity.get())
                    }
                } else {
                    println("[packageEditor] No -PmacSignIdentity: app is ad-hoc signed and will be blocked by Gatekeeper on other Macs")
                }
            }
            hostOs.isWindows -> {
                if (winConsole) cmd += "--win-console"
                if (type != "app-image") cmd += listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
            }
            else -> if (type != "app-image") cmd += listOf("--linux-package-name", "retroengine",
                                                          "--linux-shortcut", "--linux-app-category", "Development")
        }

        println("[packageEditor] ${cmd.joinToString(" ")}")
        val code = ProcessBuilder(cmd).inheritIO().start().waitFor()
        if (code != 0) throw GradleException("jpackage failed (exit $code)")
        if (hostOs.isMacOsX && signIdentity.isPresent && type == "dmg") {
            // jpackage signs the app inside the dmg but not the dmg itself
            destDir.listFiles { f -> f.name.startsWith(editorAppName) && f.name.endsWith(".dmg") }!!
                .forEach { runChecked(listOf("codesign", "--force", "--timestamp", "--sign", signIdentity.get(), it.absolutePath), "codesign dmg") }
        }
        println("[packageEditor] Output: ${destDir.absolutePath}")
    }
}

tasks.register("notarizeEditor") {
    group = "distribution"
    description = "Packages, notarizes and staples the macOS editor (needs -PmacSignIdentity and a notarytool keychain profile)."
    dependsOn("packageEditor")
    onlyIf("macOS only") { hostOs.isMacOsX }

    val identity = macSignIdentity
    val profile = providers.gradleProperty("notaryProfile").orElse("RetroEngine-notary")
    val dest = distDir

    doLast {
        if (!identity.isPresent) throw GradleException(
            "notarizeEditor needs -PmacSignIdentity=\"Developer ID Application: Name (TEAMID)\"")
        val destDir = dest.get().asFile
        val artifact = destDir.listFiles { f -> f.name.startsWith(editorAppName) }!!
            .singleOrNull() ?: throw GradleException("Expected exactly one $editorAppName artifact in $destDir")

        // notarytool accepts dmg/pkg/zip; an .app has to be zipped (ditto keeps the signature's extended attributes)
        val submission = if (artifact.name.endsWith(".app")) {
            File(destDir.parentFile, "$editorAppName-notarize.zip").also {
                it.delete()
                runChecked(listOf("ditto", "-c", "-k", "--keepParent", artifact.absolutePath, it.absolutePath), "ditto")
            }
        } else artifact

        println("[notarizeEditor] Submitting ${submission.name} (usually takes a few minutes)...")
        runChecked(listOf("xcrun", "notarytool", "submit", submission.absolutePath,
                          "--keychain-profile", profile.get(), "--wait"), "notarytool submit " +
                   "(on 'Invalid', run: xcrun notarytool log <submission-id> --keychain-profile ${profile.get()})")
        if (submission != artifact) submission.delete()

        runChecked(listOf("xcrun", "stapler", "staple", artifact.absolutePath), "stapler")
        val spctl = when {
            artifact.name.endsWith(".app") -> listOf("spctl", "--assess", "--type", "execute", "-vv", artifact.absolutePath)
            artifact.name.endsWith(".pkg") -> listOf("spctl", "--assess", "--type", "install", "-vv", artifact.absolutePath)
            else -> listOf("spctl", "--assess", "--type", "open", "--context", "context:primary-signature", "-vv", artifact.absolutePath)
        }
        runChecked(spctl, "Gatekeeper check")
        println("[notarizeEditor] Notarized and stapled: ${artifact.absolutePath}")
    }
}
