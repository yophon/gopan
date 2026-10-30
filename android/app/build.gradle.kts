import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.apollo)
}

// ---- 服务端地址:gradle.properties / -P 可覆盖,留空用默认(生产) ----
fun gopanProp(name: String, default: String): String =
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() } ?: default

val gopanBaseUrl = gopanProp("gopan.baseUrl", "https://drive.979687.xyz")
val yophonIdIssuer = gopanProp("gopan.idIssuer", "https://id.979687.xyz")
val yophonIdClientId = gopanProp("gopan.idClientId", "gopan-android")
val yophonIdRedirect = gopanProp("gopan.idRedirect", "xyz.yophon.gopan:/oauthredirect")

// ---- debug 包可选的 applicationId 后缀:手机上装着 release 签名的包(v2.6 壳或正式版)时 debug 包装不进去,
//      加后缀即可并存安装:./gradlew -Pgopan.debugAppIdSuffix=.dev :app:installDebug;默认为空,id 仍是 xyz.yophon.gopan ----
val debugAppIdSuffix = gopanProp("gopan.debugAppIdSuffix", "")

// ---- 发布签名:android/keystore.properties(gitignore)给 storeFile/storePassword/keyAlias/keyPassword;
//      没有这个文件时 release 退回 debug 签名,只能用于本地验证 ----
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "xyz.yophon.gopan"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.yophon.gopan"
        minSdk = 26
        targetSdk = 36
        versionCode = 32401
        versionName = "3.24.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 助手的 QuickJS 带原生库(doc/33 八之 2):只打 64 位真机与模拟器,不要 32 位
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        // AppAuth 的 RedirectUriReceiverActivity 由库 manifest 合并,这里只给 scheme;
        // 取自 gopan.idRedirect(如 xyz.yophon.gopan:/oauthredirect → xyz.yophon.gopan),改回调地址时两者不会脱节
        manifestPlaceholders["appAuthRedirectScheme"] = yophonIdRedirect.substringBefore(':')

        buildConfigField("String", "GOPAN_BASE_URL", "\"$gopanBaseUrl\"")
        buildConfigField("String", "YOPHON_ID_ISSUER", "\"$yophonIdIssuer\"")
        buildConfigField("String", "YOPHON_ID_CLIENT_ID", "\"$yophonIdClientId\"")
        buildConfigField("String", "YOPHON_ID_REDIRECT", "\"$yophonIdRedirect\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (debugAppIdSuffix.isNotEmpty()) applicationIdSuffix = debugAppIdSuffix
            // 测试钩子:testTag 暴露成 resource-id、放行本机明文 HTTP 的账号中心、打日志
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            buildConfigField("boolean", "TEST_HOOKS", "false")
        }
        // 与 release 同样开 R8、同一套规则,但 debug 签名、带测试钩子、允许连本机栈:
        // 用来对压缩后的包跑全部 E2E(./gradlew :app:assembleReleaseE2e -Pgopan.baseUrl=http://localhost:8090 ...)
        create("releaseE2e") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "TEST_HOOKS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---- Apollo:直接用后端仓库里的 schema 生成,operations 放 src/main/graphql ----
apollo {
    service("gopan") {
        packageName.set("xyz.yophon.gopan.graphql")
        schemaFiles.from(rootProject.file("../server/graph/schema.graphqls"))
        srcDir("src/main/graphql")
        mapScalar("Time", "kotlin.String")
        mapScalar("Int64", "kotlin.Long", "com.apollographql.apollo.api.LongAdapter")
        generateKotlinModels.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.apollo.runtime)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.appauth)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    // 助手:QuickJS 里跑 tau 内核(doc/33 八之 2)
    implementation(libs.quickjs.kt)

    // 预览:音视频(流式 + 后台播放与通知控制)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
}

// 单元测试跑在电脑的 JVM 上:QuickJS 换成带桌面原生库的 -jvm 版(Windows / Linux / macOS 都有)
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android")).using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjsKt.get()}"))
    }
}
