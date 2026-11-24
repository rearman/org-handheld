# CLAUDE.md

org-palm is an Android front end for three org-mode files: calendar.org, todo.org, & memos.org. `DESIGN.md` is the spec; read sections 3 & 4 ere changing the parser or the writer.

## Stack

* `:org-core`: a pure Kotlin JVM module (JDK 17, KGP 2.2.21) w/ no runtime dependency. It holds the lossless parser (`OrgDocument`), the timestamps & their repeats (`Timestamp`), the records (`Records.kt`), the span writer (`OrgWriter`), the escapes (`Escape`), & the rebase of an edit onto fresh text (`Rebase`), all in `com.rearman.orgpalm.org`. Tests are JUnit 4.
* `:app` (phase 2): Kotlin, Jetpack Compose, & Material 3 (AndroidX), minSdk 26.

## Building & testing

This machine has no JDK, Gradle, or Android SDK. Tests run in CI alone (`.github/workflows/build.yml`: `gradle :org-core:test` on JDK 17 & Gradle 9.4.1); verify a change by reading it.

## Fixtures

The tests compare the writer against files that Org's own commands wrote. After a change to `tools/make-fixtures.el` or to an input fixture in `org-core/src/test/resources/`, regenerate them from the repository root:

	emacs --batch -l tools/make-fixtures.el

A second run must give no diff. The Kotlin follows Emacs where Emacs & `DESIGN.md` differ. `.gitattributes` keeps the fixtures' line endings as they are.

## Conventions

* Indent w/ tabs in every file whose syntax allows it (Kotlin, `.kts`, `.el`, `.md`); `.yml` takes spaces.
* This is a jj repository. Commit, describe, or push only on the user's word.
