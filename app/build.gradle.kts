plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

// CI signs every build w/ one key, so that the APK of a later run updates an earlier install: the PKCS12
// file ORG_HANDHELD_KEYSTORE names (alias org-handheld) & its password, from the repository's secrets.
// GITHUB_RUN_NUMBER gives the rising versionCode. A local build keeps the debug key & versionCode 1.
val keystore = providers.environmentVariable("ORG_HANDHELD_KEYSTORE").orNull
val keystorePassword = providers.environmentVariable("ORG_HANDHELD_KEYSTORE_PASSWORD").orNull
val runNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

// The Android app of org-palm: the folders, the walk, & the screens, on top of :org-core. AGP compiles
// the Kotlin (built-in Kotlin), so no Kotlin Android plugin is applied.
android {
	namespace = "com.rearman.orgpalm"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.rearman.orgpalm"
		minSdk = 26
		targetSdk = 36
		versionCode = runNumber ?: 1
		versionName = "0.2"
	}

	signingConfigs {
		if (keystore != null) {
			create("ci") {
				storeFile = file(keystore)
				storeType = "pkcs12"
				storePassword = keystorePassword
				keyAlias = "org-handheld"
				keyPassword = keystorePassword
			}
		}
	}

	buildTypes {
		getByName("debug") {
			if (keystore != null) signingConfig = signingConfigs.getByName("ci")
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
