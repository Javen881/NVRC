plugins { id("com.android.application") }
android {
    namespace = "com.example.nvrclient"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.example.nvrclient"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.7.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("main") {
        jniLibs.srcDirs("../vendor/hikvision/sdk/jniLibs", "../vendor/uniview/sdk/NETDEVSDK_Android_V2.7.0.1/lib", "../vendor/dahua/dahua_net_sdk/android/src/main/jniLibs")
        java.srcDir("../vendor/uniview/sdk/NETDEVSDK_Android_V2.7.0.1/demo/app/src/main/java")
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.7.0")
    implementation(fileTree("../vendor/hikvision/sdk/libs") { include("*.jar") })
    implementation(fileTree("../vendor/dahua/dahua_net_sdk/android/libs") { include("*.jar") })
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/central")
    maxHeapSize = "2g"
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { version ->
                "NvrClient-v${version}-${variant.name}.apk"
            })
        }
    }
}
