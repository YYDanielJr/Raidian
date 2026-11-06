import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "yy.daniel.raidian"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "yy.daniel.raidian"
        minSdk = 33
        targetSdk = 37
        versionCode = 6
        versionName = "0.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//
//        // 只保留你实际需要的语言。
//        // 如果应用实际上支持中文和英文，可以这样：
//        resourceConfigurations += listOf("zh", "en")
    }

    /*
     * 为每一种 ABI 生成独立 APK。
     *
     * 最终会产生：
     * arm64-v8a
     * armeabi-v7a
     * x86_64
     * x86
     *
     * 如果你的应用只面向现代 ARM Android 设备，
     * 建议实际上只保留 arm64-v8a。
     */
    splits {
        abi {
            isEnable = true
            reset()

            include(
                "arm64-v8a",
                "armeabi-v7a",
                "x86_64",
                "x86"
            )

            isUniversalApk = false
        }
    }

    val releaseSigning = Properties().apply {
        val localFile = rootProject.file("release-signing.properties")
        if (localFile.isFile) localFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (!releaseSigning.getProperty("storePassword").isNullOrBlank() &&
            !releaseSigning.getProperty("keyPassword").isNullOrBlank()) {
            create("releaseLocal") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
        create("debugLocal") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "raidian123"
            keyAlias = "raidian"
            keyPassword = "raidian123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("releaseLocal")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            /*
             * AGP 9.x 推荐方式：
             * 开启完整的应用优化。
             *
             * 它会启用 R8 代码压缩 / 优化，
             * 并配合资源压缩减少最终 APK 大小。
             */
            optimization {
                enable = true
            }
        }

        debug {
            // 使用项目内 keystore，避免依赖 SDK 目录可写性
            signingConfig = signingConfigs.getByName("debugLocal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }

        /*
         * 推荐保持默认的非 Legacy packaging。
         *
         * 对现代 Android 来说，不需要强制压缩 .so。
         * 官方建议 Release 中保持 native libraries 非压缩，
         * 可获得更小下载体积和更快加载。
         */
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/*
 * 修改 APK 输出文件名：
 *
 * Raidian_<version>_<arch>_<debug/release>.apk
 *
 * 例如：
 * Raidian_0.1.1_arm64-v8a_release.apk
 * Raidian_0.1.1_arm64-v8a_debug.apk
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->

            val abi = output.filters
                .find {
                    it.filterType == FilterConfiguration.FilterType.ABI
                }
                ?.identifier
                ?: "universal"

            val versionName = output.versionName.orNull ?: "unknown"
            val buildType = variant.buildType ?: "unknown"

            output.outputFileName.set(
                "Raidian_${versionName}_${abi}_${buildType}.apk"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Miuix
    implementation(libs.miuix.ui)
    implementation(libs.miuix.core)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)

    // Navigation Event
    implementation(libs.androidx.navigationevent.compose)

    // AOSP wireless pairing and TLS transport; Apache-2.0 option, see THIRD_PARTY_NOTICES.md.
    implementation(project(":libadb"))
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
