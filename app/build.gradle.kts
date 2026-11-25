plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

// The Android app of org-palm: the folders, the walk, & the screens, on top of :org-core. AGP compiles
// the Kotlin (built-in Kotlin), so no Kotlin Android plugin is applied.
android {
	namespace = "com.rearman.orgpalm"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.rearman.orgpalm"
		minSdk = 26
		targetSdk = 36
		versionCode = 1
		versionName = "0.2"
	}

	// A fixed key, kept in the repository, so that each new build
	// installs over the old one and keeps your data.
	signingConfigs {
		getByName("debug") {
			storeFile = file("debug.keystore")
			storePassword = "android"
			keyAlias = "androiddebugkey"
			keyPassword = "android"
		}
	}

	buildTypes {
		getByName("debug") {
			signingConfig = signingConfigs.getByName("debug")
		}
		getByName("release") {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("debug")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
	}
}

kotlin {
	jvmToolchain(17)
}

dependencies {
	implementation(project(":org-core"))
	implementation(platform("androidx.compose:compose-bom:2025.05.00"))
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.10.1")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
	implementation("androidx.datastore:datastore-preferences:1.1.7")
	testImplementation("junit:junit:4.13.2")
}
