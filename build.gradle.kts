plugins {
    java
    id("application")
}

group = "com.retroengine"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// 1. DYNAMIC OS & ARCHITECTURE DETECTION FOR GITHUB DESKTOP PARITY
val lwjglClassifier = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    when {
        os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm64")) "natives-macos-arm64" else "natives-macos"
        os.contains("win") -> "natives-windows"
        os.contains("nix") || os.contains("nux") -> "natives-linux"
        else -> throw GradleException("Unsupported Operating System Configuration!")
    }
}

dependencies {
    // 2. Stable version alignment bill-of-materials
    implementation(platform("org.lwjgl:lwjgl-bom:3.3.5"))

    // 3. Core engine abstraction modules
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")       // Windowing + Native Controllers
    implementation("org.lwjgl:lwjgl-vulkan")     // Graphics API Pipeline
    implementation("org.lwjgl:lwjgl-openal")     // 3D Audio Positional Engine
    implementation("org.lwjgl:lwjgl-assimp")     // glTF Mesh & Rig Importer
    implementation("org.lwjgl:lwjgl-stb")        // Native Image/Texture Streaming

    // 4. Math Backbone & Workspace UI Shell
    implementation("org.joml:joml:1.10.5")
    implementation("io.github.spair:imgui-java-app:1.87.3")

    // 5. Dynamic environment native loading
    runtimeOnly("org.lwjgl:lwjgl::$lwjglClassifier")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglClassifier")
    runtimeOnly("org.lwjgl:lwjgl-openal::$lwjglClassifier")
    runtimeOnly("org.lwjgl:lwjgl-assimp::$lwjglClassifier")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglClassifier")

    // 6. FIXED: Hardcoded architectural fallback targets for the Vulkan module
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        runtimeOnly("org.lwjgl:lwjgl-vulkan:3.3.5:natives-macos-arm64") // Explicitly forces Apple Silicon ARM64 binaries
    } else {
        runtimeOnly("org.lwjgl:lwjgl-vulkan::$lwjglClassifier")
    }
}

application {
    mainClass.set("com.retroengine.Main")
}

configure<JavaApplication> {
    applicationName = "NeonMiami1980"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// 7. ENFORCE SYSTEM ENTITLEMENTS PERMISSION AT THE RUNTIME LEVEL
tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-XX:+UnlockDiagnosticVMOptions"
    )

    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}