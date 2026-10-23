@file:Suppress("SpellCheckingInspection")

plugins {
    java
    application
}

group = "com.retro.engine"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.4"

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
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.lwjgl:lwjgl-assimp")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-tinyfd")

    for (platformNative in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$platformNative")
        runtimeOnly("org.lwjgl:lwjgl-glfw::$platformNative")
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
    val isMac = org.gradle.internal.os.OperatingSystem.current().isMacOsX
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
    // 1. Check VULKAN_SDK environment variable (Windows glslc.exe and Unix glslc)
    val vulkanSdk = System.getenv("VULKAN_SDK")
    if (!vulkanSdk.isNullOrEmpty()) {
        val sdkGlslcWin = file("$vulkanSdk/bin/glslc.exe")
        if (sdkGlslcWin.exists()) return sdkGlslcWin.absolutePath
        val sdkGlslc = file("$vulkanSdk/bin/glslc")
        if (sdkGlslc.exists()) return sdkGlslc.absolutePath
    }

    // 2. Scan standard Windows Vulkan SDK install directory (e.g. C:/VulkanSDK/1.3.296.0/Bin/glslc.exe)
    val vulkanBaseDir = file("C:/VulkanSDK")
    if (vulkanBaseDir.exists()) {
        val subDirs = vulkanBaseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in subDirs) {
            val candidate = file("${dir.absolutePath}/Bin/glslc.exe")
            if (candidate.exists()) return candidate.absolutePath
        }
    }

    // 3. Fallback common paths
    val commonPaths = listOf(
        "/opt/homebrew/bin/glslc",
        "/usr/local/bin/glslc",
        "/usr/bin/glslc"
    )
    for (path in commonPaths) {
        val f = file(path)
        if (f.exists()) return f.absolutePath
    }

    // 4. System PATH execution check
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

tasks.register("compileShaders") {
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
            println("Shader compilation skipped. To compile shaders automatically:")
            println("  - macOS (Homebrew): brew install vulkan-sdk (or brew install shaderc)")
            println("  - macOS/Windows/Linux: Install Vulkan SDK")
            println("==========================================================================")

            val missingSpv = shaderFiles.filter { !file("${it.absolutePath}.spv").exists() }
            if (missingSpv.isNotEmpty()) {
                throw GradleException(
                    "Missing pre-compiled .spv files for: ${missingSpv.map { it.name }}. " +
                    "Please install the Vulkan SDK (glslc) or place pre-compiled .spv files in ${shaderDir.path}"
                )
            }
            return@doLast
        }

        shaderFiles.forEach { shader ->
            val outputFile = file("${shader.absolutePath}.spv")
            val exitCode = ProcessBuilder(glslcExec, shader.absolutePath, "-o", outputFile.absolutePath)
                .inheritIO()
                .start()
                .waitFor()

            if (exitCode == 0) {
                println("Compiled ${shader.name} -> ${outputFile.name}")
            } else {
                throw GradleException("Failed to compile ${shader.name} with $glslcExec (exit code: $exitCode)")
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn("compileShaders")
}

// ==============================================================================
// Automatic VRAM Asset Cooking Task (.rpack)
// ==============================================================================
tasks.register<JavaExec>("cookAssets") {
    group = "build"
    description = "Automatically bakes raw Assets/ into binary .rpack packages using RpackCookerCli."
    dependsOn(tasks.named("classes"))

    mainClass.set("com.retro.engine.assets.cooker.RpackCookerCli")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--dir", "Assets", "--out", "Cooked")
}

// Automatically execute asset cooking whenever you run the engine
tasks.named("run") {
    dependsOn("cookAssets")
}
