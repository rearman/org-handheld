// The kotlin.jvm entry puts KGP 2.2.21 on the build classpath for :org-core. The Android plugins come
// w/ the :app module in phase 2.
plugins {
	id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
}
