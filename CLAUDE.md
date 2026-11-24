# CLAUDE.md

org-palm is an Android front end for any org file in the folders the user picks, every heading an editable entry. The user syncs those folders w/ git in Termux, so the app writes no `:ID:` & keeps no rebase: each save re-reads the file, finds the heading by its anchor, & writes at once. `DESIGN.md` is the spec; read sections 3 & 4 ere changing the parser or the writer. `PLAN.md` holds the remaining phases, the ground rules, & the known limits of org-core; read it ere starting a phase.

## Stack

* `:org-core`: a pure Kotlin JVM module (JDK 17, KGP 2.2.21) w/ no runtime dependency. It holds the lossless parser (`OrgDocument`), the timestamps & their repeats (`Timestamp`), the entries & files (`Records.kt`), the mirrored Emacs settings (`OrgSettings`), the diary sexps (`DiarySexp`), the span writer (`OrgWriter`), the form text written as typed (`FormText`), & the anchor that finds an edited heading in fresh text (`Anchor`), all in `com.rearman.orgpalm.org`. Tests are JUnit 4.
* `:app`: Kotlin, Jetpack Compose, & Material 3 (AndroidX), minSdk 26, built by AGP 9.2.0 w/ its built-in Kotlin. It holds the folder walk & the file cache (`Walk.kt`), the SAF access (`Storage.kt`), the stored settings & folders (`Stores.kt`, DataStore), & the screens, in `com.rearman.orgpalm`.

## Building & testing

This machine has no JDK, Gradle, or Android SDK. Tests run in CI alone (`.github/workflows/build.yml`: `gradle :org-core:test`, then `gradle :app:test :app:assembleDebug`, on JDK 17 & Gradle 9.4.1); verify a change by reading it.

## Fixtures

The tests compare the writer against files that Org's own commands wrote. After a change to `tools/make-fixtures.el` or to an input fixture in `org-core/src/test/resources/`, regenerate them from the repository root:

	emacs --batch -l tools/make-fixtures.el

A second run must give no diff. The Kotlin follows Emacs where Emacs & `DESIGN.md` differ. `.gitattributes` keeps the fixtures' line endings as they are.

## Conventions

* Indent w/ tabs in every file whose syntax allows it (Kotlin, `.kts`, `.el`, `.md`); `.yml` takes spaces.
* This is a jj repository. Commit, describe, or push only on the user's word.
