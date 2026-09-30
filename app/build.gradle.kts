import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val mapsApiKey = localProperties.getProperty("MAPS_API_KEY")?.trim()
    ?.takeIf { it.isNotBlank() && !it.contains("YOUR_GOOGLE_MAPS_API_KEY") }
    ?: "DUMMY_KEY_BUILD_WITHOUT_MAPS"

if (mapsApiKey.startsWith("DUMMY_KEY")) {
    logger.warn(
        """
        ⚠️ ไม่พบ MAPS_API_KEY ใน local.properties — แผนที่จะแสดงเป็นพื้นสีเทา
        วิธีตั้งค่า: สร้างไฟล์ local.properties ที่รากโปรเจกต์ แล้วใส่
            MAPS_API_KEY=คีย์ของคุณ
        (ดูตัวอย่างได้ใน local.properties.example) แล้วกด Sync ใหม่
        """.trimIndent()
    )
}

android {
    namespace = "com.patipan.tripmap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.patipan.tripmap"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.5.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.locationtech.jts:jts-core:1.19.0")
}