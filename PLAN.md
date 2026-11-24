# org-palm: the plan from phase 5 on

`DESIGN.md` is the spec; this file is the order of work that builds it. Phases 1, 1b, 2, 3, & 4 are done: `org-core` parses any org file losslessly, reads every heading as an `Entry`, writes edits as Org's own commands write them under the user's Emacs settings, & finds an edited heading in fresh text by its anchor; the app walks the picked folders, shows the Agenda, Day, Week, & Month, & edits, captures, & refiles entries through the bottom sheet, w/ a settings page. CI (`gradle :org-core:test`, then `gradle :app:test :app:assembleDebug`) is green on `17323d0d` (2026-11-24), the end of phase 4.

## Ground rules for every phase

* **Build only what the user asks for.** Each rule, field, check, & screen traces to a request or an approval of the user's; any other idea goes to the user in one line first.
* **Interoperability w/ Emacs comes first.** The app reads & writes a file as Org would, under the user's settings. Where Emacs & `DESIGN.md` differ, the code follows Emacs, & `DESIGN.md` is corrected in the same change. The app invents no structure or behavior that Org lacks; form text goes into the file as typed, & Org reads it as it would read the same text typed in Emacs.
* **The settings come from the user's Emacs init**, `~/.emacs.d/init.el` (the Org settings sit near its lines 185 to 265). `OrgSettings.STARTING` & the settings block atop `tools/make-fixtures.el` (under which every case runs) mirror it, & the table in `DESIGN.md` section 3 ("Emacs settings") lists the values. Ere each phase, reread the Org settings of `init.el`; a changed value changes all three in one pass. (On 2026-11-24 the capture templates gained `:prepend t`, which set the capture position to first.)
* **Sync is git in Termux:** a pull, a string of edits, a push. The app writes no `:ID:` & keeps no state that a sync could break.
* **Every write test compares against Emacs.** A new write path gets a case in `tools/make-fixtures.el`, run by Org's own command under the user's settings (a case that tests another value of one variable sets that variable alone), & a test that compares bytes. One test per behavior. Run `emacs --batch -l tools/make-fixtures.el` twice; the second run must give no diff.
* **No local JDK, Gradle, or Android SDK.** CI is the compiler. Read each change for compile errors ere pushing.
* **jj repository.** Commit & push only on the user's word.

## Phase 5: saved agenda filters

Named filters, each a set of TODO states, tags, categories, & a date span, like an entry in `org-agenda-custom-commands`; the overflow menu picks one. The starting set mirrors the two commands of `init.el`: "GTD Dashboard" (today's agenda, the `inbox` tag, NEXT, WAITING, & stuck projects per `org-stuck-projects`) & "Contexts" (NEXT entries grouped by tag, sorted as its `org-agenda-sorting-strategy` gives: tag up, priority down, effort up). Reread them in `init.el` ere building, as with the settings.

## Phase 6: alarms

Alarms from `:APPT_WARNTIME:` (minutes ere the start of a timed event), as `org-agenda-to-appt` reads it. Schedule the next occurrence of each w/ `AlarmManager` exact alarms (`SCHEDULE_EXACT_ALARM`, or `USE_EXACT_ALARM` if the app qualifies), post through a notification channel, & reschedule after each walk, each save, & a reboot (`BOOT_COMPLETED`).

## Phase 7: the outline browser

Any file's headings as a collapsible tree; a tap opens the sheet. The same tree serves as the file & heading picker for capture & refile, replacing the phase 4 list.

## Known limits of org-core

Each is marked in a code comment, w/ its upgrade path.

* Agenda: an active stamp inside inline verbatim or code (`=...=`, `~...~`) still counts.
* Diary sexps are read only inside `<%%(...)>`, not as a bare `%%(` line; only five forms are read (`diary-anniversary`, `diary-block`, `diary-cyclic`, `diary-date`, `diary-float`), & their dates are tested one day at a time over the window.
* The `LOG_INTO_DRAWER` & `LOGGING` properties are not read; the settings & `#+STARTUP:` govern logging.
* A checkbox inside a block still blocks a done state; a `#+KEY:` line inside a block still counts as a setting.
* A `:CATEGORY:` in a drawer above the first heading, or set by `#+PROPERTY:`, is not read.
* One save that changes the state & a date logs two notes, as two Emacs commands would; Org keeps only the last note set up within one command.
* `Anchor.of` scans every heading for twins: O(n²) per file; a map of heading line to count, built once per `OrgFile`, is the upgrade.
