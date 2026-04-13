plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.gpxit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.gpxit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("foss") {
            dimension = "variant"
            // No Google Play Services — F-Droid compatible
        }
        create("full") {
            dimension = "variant"
            // Includes Google Play Services for better location
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)

    implementation(libs.osmdroid)
    implementation(libs.gpx.parser) {
        exclude(group = "xmlpull")
        exclude(group = "xpp3")
        exclude(group = "net.sf.kxml", module = "kxml2")
    }
    implementation(libs.public.transport.enabler) {
        exclude(group = "xmlpull")
        exclude(group = "xpp3")
        exclude(group = "net.sf.kxml", module = "kxml2")
    }

    // Google Play Services location — only for full flavor
    "fullImplementation"(libs.play.services.location)
}
