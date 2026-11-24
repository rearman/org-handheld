plugins {
	id("org.jetbrains.kotlin.jvm")
}

// The Org parser, entry model, writer, and anchors of org-palm: pure JVM, w/ no runtime dependency.
kotlin {
	jvmToolchain(17)
}

dependencies {
	testImplementation("junit:junit:4.13.2")
}
