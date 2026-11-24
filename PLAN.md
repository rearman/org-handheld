# org-palm: the plan from phase 2 on

`DESIGN.md` is the spec; this file is the order of work that builds it. Phases 1 & 1b are done: `org-core` parses any org file losslessly, reads every heading as an `Entry`, writes edits as Org's own commands write them under the user's Emacs settings, & finds an edited heading in fresh text by its anchor. CI (`gradle :org-core:test`) ran green on the 1b push of 2026-11-24; the later 1b changes (one test group under the user's settings, & form text written as typed) await their first CI run.

## Ground rules for every phase

* **Build only what the user asks for.** Each rule, field, check, & screen traces to a request or an approval of the user's; any other idea goes to the user in one line first.
* **Interoperability w/ Emacs comes first.** The app reads & writes a file as Org would, under the user's settings. Where Emacs & `DESIGN.md` differ, the code follows Emacs, & `DESIGN.md` is corrected in the same change. The app invents no structure or behavior that Org lacks; form text goes into the file as typed, & Org reads it as it would read the same text typed in Emacs.
* **The settings come from the user's Emacs init**, `~/.emacs.d/init.el` (the Org settings sit near its lines 185 to 265). `OrgSettings.STARTING` & the settings block atop `tools/make-fixtures.el` (under which every case runs) mirror it, & the table in `DESIGN.md` section 3 ("Emacs settings") lists the values. Ere each phase, reread the Org settings of `init.el`; a changed value changes all three in one pass. (On 2026-11-24 the capture templates gained `:prepend t`, which set the capture position to first.)
* **Sync is git in Termux:** a pull, a string of edits, a push. The app writes no `:ID:` & keeps no state that a sync could break.
* **Every write test compares against Emacs.** A new write path gets a case in `tools/make-fixtures.el`, run by Org's own command under the user's settings (a case that tests another value of one variable sets that variable alone), & a test that compares bytes. One test per behavior. Run `emacs --batch -l tools/make-fixtures.el` twice; the second run must give no diff.
* **No local JDK, Gradle, or Android SDK.** CI is the compiler. Read each change for compile errors ere pushing.
* **jj repository.** Commit & push only on the user's word.

## Phase 2: folders, the walk, & the Agenda (read-only)

1. **The `:app` module.** Add the Android Gradle plugin & the Kotlin Compose plugin to the root `build.gradle.kts` (`apply false`), include `:app` in `settings.gradle.kts`, & make `:app` depend on `:org-core`. Kotlin, Jetpack Compose, Material 3, minSdk 26, JDK 17. Update the comments in both Gradle files that say ":app joins in phase 2".
2. **CI.** Add a step `gradle :app:assembleDebug` to `.github/workflows/build.yml` (the Ubuntu runner carries an Android SDK), & keep `:org-core:test`.
3. **Settings store.** Persist `OrgSettings` (Jetpack DataStore), seeded w/ `OrgSettings.STARTING`. The settings page itself may wait for phase 4, but the stored values feed every parse from this phase on.
4. **Folder list.** Pick folders w/ `ACTION_OPEN_DOCUMENT_TREE`; keep each tree URI w/ `takePersistableUriPermission` (read & write). Termux's home appears in the picker through its DocumentsProvider. A folder reads flat by default, as `org-agenda-files` reads `~/org`; a switch per folder adds subfolders, recursively. Skip dot-files & dot-folders.
5. **The walk & the cache.** On start & on each return to the front, walk the folders (`DocumentsContract` child queries), & re-read each `.org` file whose size or last-modified time changed; drop files that are gone. Keep one `OrgFile` per document URI. Read as UTF-8; `OrgFile` keeps the BOM & line endings.
6. **The Agenda zoom level.** A day-by-day list from `Entry.agendaDates(from, to)` & the todo list from `Entry.inTodoList` (every open TODO, dated or not; COMMENT & `:ARCHIVE:` subtrees skipped). Show time, title, state, priority, tags (own & inherited), & category. A tap does naught yet.
7. **Tests.** Unit tests for the walk's filter (dot-files, flat vs. recursive) & the change check (size & time); no UI tests.

## Phase 3: Day, Week, & Month

1. A row of chips (Agenda, Day, Week, Month) picks the level; a pinch steps it (pinch in toward Month, spread out toward Agenda). The date in view stays centered across a change of level.
2. **Day**: an hour grid, untimed entries in a band atop it. **Week**: seven day columns on one hour grid. **Month**: a grid of weeks; a tap on a day zooms to Day.
3. A date range spans its days; a repeating stamp expands per `Timestamp.occurrences`, as Org's agenda expands it.

## Phase 4: the bottom sheet, capture, & edit

1. **The sheet**: one form for capture & edit (`DESIGN.md` section 2). Fields: title, state, priority, scheduled, deadline, tags as chips, body, & the file & heading that hold it. An entry whose leading stamp lines match the event shapes adds date, time span, repeat, weekday set, & alarm (`Entry.event`); its body field shows `Event.body`.
2. **Save**: read the file fresh, apply the `Edit` w/ `OrgWriter(clock, settings)`, & write the whole file at once (`openOutputStream(uri, "wt")`). Results:
	* `Written`: replace the cached `OrgFile`.
	* `Conflict`: keep the form open w/ the edit, re-read, & offer a retry.
	* `Locked`: the field was locked at read; refresh the form.
	* `Blocked(reason)`: show the reason (an open child or an unchecked box, per the enforced dependencies).
3. **Notes**: a state whose keyword asks for a note (`@`) opens a note field; an empty note logs the line alone.
4. **Capture**: the default target starts at `inbox.org` at the top level, placed per the capture position (first, from `:prepend t`). A simple file & heading list serves as the picker until phase 7. A missing file or heading sends the user to the picker; the app creates a file only on request (`createDocument`).
5. **Refile**: within a file one `Edit.Refile`; across files `OrgWriter.subtree`, then `refileIn` written to the target, then `refileOut` written to the source. A `Conflict` from `refileOut` leaves the copy in the target & tells the user.
6. **The settings page**: every row of the Emacs settings table, plus the capture target & each folder's subfolder switch.

## Phase 5: saved agenda filters

Named filters, each a set of TODO states, tags, categories, & a date span, like an entry in `org-agenda-custom-commands`; the overflow menu picks one. The starting set mirrors the two commands of `init.el`: "GTD Dashboard" (today's agenda, the `inbox` tag, NEXT, WAITING, & stuck projects per `org-stuck-projects`) & "Contexts" (NEXT entries grouped by tag, sorted as its `org-agenda-sorting-strategy` gives: tag up, priority down, effort up). Reread them in `init.el` ere building, as with the settings.

## Phase 6: alarms

Alarms from `:APPT_WARNTIME:` (minutes ere the start of a timed event), as `org-agenda-to-appt` reads it. Schedule the next occurrence of each w/ `AlarmManager` exact alarms (`SCHEDULE_EXACT_ALARM`, or `USE_EXACT_ALARM` if the app qualifies), post through a notification channel, & reschedule after each walk, each save, & a reboot (`BOOT_COMPLETED`).

## Phase 7: the outline browser

Any file's headings as a collapsible tree; a tap opens the sheet. The same tree serves as the file & heading picker for capture & refile, replacing the phase 4 list. An entry w/ a diary sexp the app cannot read (`Entry.unknownSexp`) carries a marker here.

## Known limits of org-core

Each is marked in a code comment, w/ its upgrade path.

* Agenda: an active stamp inside inline verbatim or code (`=...=`, `~...~`) still counts.
* Diary sexps are read only inside `<%%(...)>`, not as a bare `%%(` line; only five forms are read (`diary-anniversary`, `diary-block`, `diary-cyclic`, `diary-date`, `diary-float`), & their dates are tested one day at a time over the window.
* The `LOG_INTO_DRAWER` & `LOGGING` properties are not read; the settings & `#+STARTUP:` govern logging.
* A checkbox inside a block still blocks a done state; a `#+KEY:` line inside a block still counts as a setting.
* A `:CATEGORY:` in a drawer above the first heading, or set by `#+PROPERTY:`, is not read.
* One save that changes the state & a date logs two notes, as two Emacs commands would; Org keeps only the last note set up within one command.
* `Anchor.of` scans every heading for twins: O(n²) per file; a map of heading line to count, built once per `OrgFile`, is the upgrade.
