# org-palm: the plan, all phases done

`DESIGN.md` is the spec; this file is the order of work that builds it. Phases 1 to 7 (w/ 1b) are done: `org-core` parses any org file losslessly, reads every heading as an `Entry`, writes edits as Org's own commands write them under the user's Emacs settings, & finds an edited heading in fresh text by its anchor; the app walks the picked folders, shows the Agenda, Day, Week, & Month, & edits, captures, & refiles entries through the bottom sheet, w/ a settings page, the saved agenda filters, alarms from `:APPT_WARNTIME:`, & the outline browser. CI (`gradle :org-core:test`, then `gradle :app:test :app:assembleDebug`) is green on `fef51afe` (2026-11-24), the end of phase 7. The ground rules & the known limits below still govern any later work.

## Ground rules for every phase

* **Build only what the user asks for.** Each rule, field, check, & screen traces to a request or an approval of the user's; any other idea goes to the user in one line first.
* **Interoperability w/ Emacs comes first.** The app reads & writes a file as Org would, under the user's settings. Where Emacs & `DESIGN.md` differ, the code follows Emacs, & `DESIGN.md` is corrected in the same change. The app invents no structure or behavior that Org lacks; form text goes into the file as typed, & Org reads it as it would read the same text typed in Emacs.
* **The settings come from the user's Emacs init**, `~/.emacs.d/init.el` (the Org settings sit near its lines 185 to 265). `OrgSettings.STARTING` & the settings block atop `tools/make-fixtures.el` (under which every case runs) mirror it, & the table in `DESIGN.md` section 3 ("Emacs settings") lists the values. Ere each phase, reread the Org settings of `init.el`; a changed value changes all three in one pass. (On 2026-11-24 the capture templates gained `:prepend t`, which set the capture position to first.)
* **Sync is git in Termux:** a pull, a string of edits, a push. The app writes no `:ID:` & keeps no state that a sync could break.
* **Every write test compares against Emacs.** A new write path gets a case in `tools/make-fixtures.el`, run by Org's own command under the user's settings (a case that tests another value of one variable sets that variable alone), & a test that compares bytes. One test per behavior. Run `emacs --batch -l tools/make-fixtures.el` twice; the second run must give no diff.
* **No local JDK, Gradle, or Android SDK.** CI is the compiler. Read each change for compile errors ere pushing.
* **jj repository.** Commit & push only on the user's word.

## Known limits of org-core

Each is marked in a code comment, w/ its upgrade path.

* Agenda: an active stamp inside inline verbatim or code (`=...=`, `~...~`) still counts.
* Diary sexps are read inside `<%%(...)>` & on a line that starts w/ `%%(`; only six forms are read (`diary-anniversary`, `diary-block`, `diary-cyclic`, `diary-date`, `diary-float`, `org-anniversary`), & their dates are tested one day at a time over the window. The `%d` of a sexp line's text is not filled w/ the years.
* The `LOG_INTO_DRAWER` & `LOGGING` properties are not read; the settings & `#+STARTUP:` govern logging.
* A checkbox inside a block still blocks a done state; a `#+KEY:` line inside a block still counts as a setting.
* A `:CATEGORY:` in a drawer above the first heading, or set by `#+PROPERTY:`, is not read.
* One save that changes the state & a date logs two notes, as two Emacs commands would; Org keeps only the last note set up within one command.
* `Anchor.of` scans every heading for twins: O(n²) per file; a map of heading line to count, built once per `OrgFile`, is the upgrade.
