plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.apkstoapk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apkstoapk.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true

        // Full CPython for Chaquopy (not Jython)
        // Device/runtime target: arm64-v8a only
        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // assets/debug23.keystore 是 BKS（Android 用），AGP 读不了会报 toDerInputStream rejects tag type 0。
        // 用同密钥导出的 PKCS12：app/debug.keystore.p12
        create("local") {
            val ksP12 = file("debug.keystore.p12")
            val ksLegacy = file("src/main/assets/debug23.keystore")
            storeFile = if (ksP12.isFile) ksP12 else ksLegacy
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // AGP 7+：显式 PKCS12，避免被当成 JKS
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("local")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/INDEX.LIST"
        resources.excludes += "META-INF/*.SF"
        resources.excludes += "META-INF/*.DSA"
        resources.excludes += "META-INF/*.RSA"
        // baksmali 双依赖（org.smali + jadx 传递的 com.android.tools.smali）
        resources.excludes += "baksmali.properties"
        resources.excludes += "smali.properties"
        resources.excludes += "dexlib2.properties"
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

chaquopy {
    defaultConfig {
        // Full CPython 3.x runtime embedded in the APK
        version = "3.11"
        pip {
            // stdlib is included; add packages here when needed
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // MCP HTTP transport (Service → Ktor CIO → POST /mcp → hand-written JSON-RPC)
    val ktorVersion = "2.3.13"
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")

    // ARSCLib: REAndroid classes + /frameworks/android/android-23..36.apk
    implementation(files("libs/ARSCLib-1.3.9.jar"))

    // Lua runtime (LuaJ 5.2 VM + LuaJC lua→Java bytecode — not a shell wrapper)
    implementation("org.luaj:luaj-jse:3.0.1")
    // LuaJC JavaBuilder needs BCEL (classfile gen) + ASM
    implementation("org.apache.bcel:bcel:6.10.0")
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-util:9.7")

    // Java source → bytecode (Janino real compiler; not BeanShell / not shell)
    // ECJ needs javax.tools/annotation.processing which JDK seals and Android lacks.
    implementation("org.codehaus.janino:janino:3.1.12")
    // class → dex on device, then InMemoryDexClassLoader
    implementation("com.android.tools:r8:8.7.18")

    // C++ toolchain installer: extract real NDK .7z (clang++) on device
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")

    // DEX browse + smali / Java decompile
    // https://github.com/skylot/jadx/wiki/Use-jadx-as-a-library
    // 使用 Android 工具链 fork，与 jadx 传递依赖统一，避免 org.smali 双份冲突
    implementation("com.android.tools.smali:smali-dexlib2:3.0.8")
    implementation("com.android.tools.smali:smali-baksmali:3.0.8")
    // smali assembler: arbitrary .smali → ClassDef / dex (compile_smali fallback)
    implementation("com.android.tools.smali:smali:3.0.8")
    implementation("com.google.guava:guava:33.0.0-android")
    implementation("io.github.skylot:jadx-core:1.5.1") {
        exclude(group = "org.smali")
    }
    implementation("io.github.skylot:jadx-dex-input:1.5.1") {
        exclude(group = "org.smali")
    }
}

// R8 lives on Google's Maven; ensure resolution if mirror lacks it
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools" && requested.name == "r8") {
            useVersion("8.7.18")
        }
    }
}

// 构建完成后，把可安装 APK 统一收集到项目根 dist/
val distDir = rootProject.layout.projectDirectory.dir("dist")

fun copyApkToDist(variantName: String, apkFile: File) {
    if (!apkFile.isFile) return
    distDir.asFile.mkdirs()
    val versionName = android.defaultConfig.versionName ?: "0"
    val versionCode = android.defaultConfig.versionCode ?: 0
    val outName = "ApksToApk-${variantName}-v${versionName}-${versionCode}.apk"
    val outFile = distDir.file(outName).asFile
    apkFile.copyTo(outFile, overwrite = true)
    // 同步一份 latest 方便找
    val latest = distDir.file("ApksToApk-${variantName}-latest.apk").asFile
    apkFile.copyTo(latest, overwrite = true)
    println("dist: ${outFile.absolutePath}")
}

afterEvaluate {
    listOf("debug", "release").forEach { variant ->
        val assemble = tasks.findByName(
            "assemble${variant.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
        ) ?: return@forEach
        assemble.doLast {
            val outDir = file("build/outputs/apk/$variant")
            // 优先正式命名；兼容旧 unsigned / _sign
            val candidates = listOf(
                file("$outDir/app-$variant.apk"),
                file("$outDir/app-$variant-unsigned_sign.apk"),
                file("$outDir/app-$variant-unsigned.apk")
            )
            val apk = candidates.firstOrNull { it.isFile }
            if (apk != null) {
                copyApkToDist(variant, apk)
            } else {
                println("dist: no apk found under $outDir")
            }
        }
    }
}
