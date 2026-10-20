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
    applicationDefaultJvmArgs = listOf(
        "-XstartOnFirstThread",
        "-Xms256m",
        "-Xmx512m",
        "-Dorg.lwjgl.vulkan.libname=${project.projectDir}/libs/macos/libMoltenVK.dylib",
        "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

fun findGlslcExecutable(): String? {
    val vulkanSdk = System.getenv("VULKAN_SDK")
    if (!vulkanSdk.isNullOrEmpty()) {
        val sdkGlslc = file("$vulkanSdk/bin/glslc")
        if (sdkGlslc.exists()) return sdkGlslc.absolutePath
    }

    val commonPaths = listOf(
        "/opt/homebrew/bin/glslc",
        "/usr/local/bin/glslc",
        "/usr/bin/glslc"
    )
    for (path in commonPaths) {
        if (file(path).exists()) return path
    }

    return try {
        val testProc = ProcessBuilder("glslc", "--version").start()
        if (testProc.waitFor() == 0) "glslc" else null
    } catch (e: Exception) {
        null
    }
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
