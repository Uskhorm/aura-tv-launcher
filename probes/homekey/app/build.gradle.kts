plugins { id("com.android.application") }
android {
    namespace = "tv.aura.probe.homekey"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "tv.aura.probe.homekey"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
