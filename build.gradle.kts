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

    // In-Memory Janino Scripting Compiler
    implementation("org.codehaus.janino:janino:3.1.12")

    for (platformNative in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-sdl::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-assimp::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-stb::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-tinyfd::$platformNative")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.retro.engine.Main")
    val isMac = OperatingSystem.current().isMacOsX
    val jvmArgsList = mutableListOf(
        "-Xms256m",
        "-Xmx512m",
        "-Dorg.lwjgl.system.stack.size=128", // Boosts LWJGL thread stack capacity
        "--enable-native-access=ALL-UNNAMED"
    )

    if (isMac) {
        jvmArgsList.add("-XstartOnFirstThread")
        jvmArgsList.add("-Dorg.lwjgl.vulkan.libname=${project.projectDir}/libs/macos/libMoltenVK.dylib")
    }

    applicationDefaultJvmArgs = jvmArgsList
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
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

    mainClass.set("com.retro.engine.assets.cooker.RpackCookerCli")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--dir", "Assets", "--out", "Cooked")
}

tasks.named("run") {
    dependsOn("cookAssets")
}
