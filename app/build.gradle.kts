plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.local.unlocksession"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.unlocksession"
        // 目标设备 OPPO R15x 是 Android 10 / API 29；compileSdk 36 只是本机已装平台的编译上限，
        // 不改变设备上的执行链路
        minSdk = 29
        // targetSdk = 29：与目标设备 Android 10 的实际执行链路一致（前台服务无需类型声明、
        // 精确闹钟无 API 31 限制、通知无运行时权限），单机侧载、不上架
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 单机侧载：release 也用 debug 签名，保证可直接安装（与工作区其他项目约定一致）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 29 在高版本 lint 下会报一批与本机需求无关的告警，构建不因此失败
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
}
