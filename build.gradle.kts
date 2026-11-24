// The kotlin.jvm entry puts KGP 2.2.21 on the build classpath for :org-core. AGP 9.2.0 (the release
// for Gradle 9.4.1) builds :app & compiles its Kotlin itself (built-in Kotlin), w/ the Compose
// compiler plugin of the same KGP.
plugins {
	id("com.android.application") version "9.2.0" apply false
	id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
	id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
