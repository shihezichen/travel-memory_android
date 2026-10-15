plugins {
    id("com.android.application")
}

android {
    namespace = "xin.top001.travelmemory"
    compileSdk = 35

    defaultConfig {
        applicationId = "xin.top001.travelmemory"
        minSdk = 24
        targetSdk = 35
        versionCode = 1000000
        versionName = "1.0.0"
    }

    /**
     * 分发用的自签名密钥。
     *
     * Android 允许自签名安装（对方需要打开「允许安装未知应用」），不像鸿蒙那样
     * 必须走华为开发者账号。仓库里**不放密钥库**——keystore/ 在 .gitignore 里；
     * 首次构建前按 README 用 keytool 生成一次即可。
     */
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/travel-memory.jks")
            storePassword = "travelmemory"
            keyAlias = "travelmemory"
            keyPassword = "travelmemory"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 只为了 FileProvider（把拍照目标 URI 安全地交给相机 App）和几个兼容工具。
    // 没有用 AppCompat，界面就是原生 WebView，保持包小。
    implementation("androidx.core:core:1.13.1")
}
