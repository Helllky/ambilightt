plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

// Иконка приложения создаётся прямо при сборке
fun writeRes(path: String, text: String) {
    val f = File(projectDir, "src/main/res/" + path)
    f.parentFile.mkdirs()
    f.writeText(text)
}

writeRes("mipmap-anydpi/ic_launcher.xml", """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_bg" />
    <foreground android:drawable="@drawable/ic_fg" />
</adaptive-icon>
""")

writeRes("drawable/ic_bg.xml", """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FFFFB36B" />
                <item android:offset="0.55" android:color="#FFF2698A" />
                <item android:offset="1" android:color="#FF6A4FB8" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""")

writeRes("drawable/ic_fg.xml", """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#33FFFFFF"
        android:pathData="M54,50m-26,0a26,26 0 1,0 52,0a26,26 0 1,0 -52,0" />
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M54,29C43.5,29 36,36.8 36,46.5C36,52.5 38.8,56.6 41.8,60C43.6,62.1 44.5,64 44.5,66.5L63.5,66.5C63.5,64 64.4,62.1 66.2,60C69.2,56.6 72,52.5 72,46.5C72,36.8 64.5,29 54,29Z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M45,69.5h18v3h-18z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M47,75h14v3h-14z" />
    <path android:fillColor="#FFFFC58A"
        android:pathData="M54,44m-8,0a8,8 0 1,0 16,0a8,8 0 1,0 -16,0" />
    <path android:fillColor="#FFFFC58A" android:pathData="M52.5,51h3v13h-3z" />
</vector>
""")

android {
    namespace = "com.example.ambilight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.ambilight"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Постоянная подпись: новые версии ставятся поверх старых без удаления
    if (File(projectDir, "debug.keystore").exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = File(projectDir, "debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
}
