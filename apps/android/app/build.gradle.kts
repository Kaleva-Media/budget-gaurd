import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun collectorSetting(name: String): String =
    providers.environmentVariable(name).orNull
        ?: localProperties.getProperty(name)
        ?: ""

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val supabaseUrl = collectorSetting("BUDGET_GUARD_SUPABASE_URL")
val supabasePublishableKey = collectorSetting("BUDGET_GUARD_SUPABASE_PUBLISHABLE_KEY")
val buildsInstallableApp = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':').lowercase()
    taskName.startsWith("assemble") ||
        taskName.startsWith("bundle") ||
        taskName.startsWith("install") ||
        taskName.startsWith("package")
}

if (buildsInstallableApp) {
    if (supabaseUrl.isBlank()) {
        throw GradleException(
            "BUDGET_GUARD_SUPABASE_URL is required to build an installable BudgetGuard app.",
        )
    }
    if (supabasePublishableKey.isBlank()) {
        throw GradleException(
            "BUDGET_GUARD_SUPABASE_PUBLISHABLE_KEY is required to build an installable BudgetGuard app.",
        )
    }
}

android {
    namespace = "app.budgetguard.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.budgetguard.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.6.0"

        buildConfigField("String", "SUPABASE_URL", supabaseUrl.asBuildConfigString())
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            supabasePublishableKey.asBuildConfigString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":parser"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-android:3.0.3")

    testImplementation("junit:junit:4.13.2")
}
