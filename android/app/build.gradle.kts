import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Phase 2.c: read Alpaca **test stream** credentials from
 * `local.properties` (already gitignored). Both keys are optional;
 * blank values map to "no credentials configured" at runtime,
 * which the boundary surfaces as `AuthenticationFailed`.
 *
 * Keys are never committed to source. Release builds explicitly
 * receive empty values regardless of `local.properties` contents
 * (see the `release` buildType below).
 */
fun loadLocalAlpacaProperty(propertyName: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use(props::load)
    return props.getProperty(propertyName, "").trim()
}

fun loadLocalBooleanProperty(propertyName: String): Boolean =
    loadLocalAlpacaProperty(propertyName).equals("true", ignoreCase = true)

android {
    namespace = "com.vela.android.lab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vela.android.lab"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default credentials are blank. `debug` overrides these from
        // `local.properties`; `release` keeps them blank by force.
        buildConfigField("String", "ALPACA_TEST_KEY_ID", "\"\"")
        buildConfigField("String", "ALPACA_TEST_SECRET", "\"\"")
        // Phase 2.v is compiled fail-closed. A debug developer must
        // explicitly opt in through uncommitted local.properties and
        // the user must still arm the in-memory session gate.
        buildConfigField("boolean", "MANUAL_PAPER_SUBMIT_COMPILED", "false")
    }

    buildTypes {
        getByName("debug") {
            // Debug-only: pull values from the developer's local
            // (gitignored) `local.properties`. Never logged.
            val keyId = loadLocalAlpacaProperty("ALPACA_TEST_KEY_ID")
            val secret = loadLocalAlpacaProperty("ALPACA_TEST_SECRET")
            buildConfigField("String", "ALPACA_TEST_KEY_ID", "\"$keyId\"")
            buildConfigField("String", "ALPACA_TEST_SECRET", "\"$secret\"")
            val manualPaperCompiled =
                loadLocalBooleanProperty("MANUAL_PAPER_SUBMIT_COMPILED")
            buildConfigField(
                "boolean",
                "MANUAL_PAPER_SUBMIT_COMPILED",
                manualPaperCompiled.toString(),
            )
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds NEVER carry credentials, regardless of
            // what `local.properties` happens to contain.
            buildConfigField("String", "ALPACA_TEST_KEY_ID", "\"\"")
            buildConfigField("String", "ALPACA_TEST_SECRET", "\"\"")
            buildConfigField("boolean", "MANUAL_PAPER_SUBMIT_COMPILED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // UX-2: local, non-sensitive visual preferences only. Trading settings,
    // credentials, endpoints, gates, and execution state are intentionally absent.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Phase 2.b: WebSocket + JSON for the Alpaca Market Data test stream.
    // OkHttp is the only HTTP/WebSocket library on the classpath. No
    // Retrofit, no Ktor, no Alpaca SDK. The Market Data boundary in
    // `data/market/source/alpaca/` is the ONLY caller of OkHttp.
    implementation(libs.okhttp)
    implementation(libs.org.json)

    // Phase 2.c.1: Keystore-backed EncryptedSharedPreferences for the
    // in-app Alpaca credentials settings flow. Provides AES-256-GCM
    // value encryption with the master key sealed by the Android
    // Keystore (hardware-backed on devices that support it).
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Test JVM tuned for low-memory hosts: serial GC + small heap keeps
    // virtual address space reservation under the Windows page-file
    // commit limit while still leaving headroom for the test classpath.
    maxHeapSize = "384m"
    jvmArgs = listOf("-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=256m")
    maxParallelForks = 1
}
