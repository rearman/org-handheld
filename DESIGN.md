# org-palm: design

An Android front end for org-mode data. The phone shows calendars & a dynamic agenda first, edits records through forms w/o Emacs keystrokes, & keeps its files in a form that Emacs opens, edits, & hands back.

## 1. Goals & limits

1. **Viewing first.** The home screen is a timeline that shows the agenda & the calendar; editing is a step taken from it.
2. **Form editing.** Every edit goes through a form w/ labeled fields, pop-up lists, & date pickers. The user never types org syntax on the phone.
3. **Emacs round trip.** The files live in folders that the user syncs w/ git through Termux (Syncthing or the like serves too). Emacs may edit any of them; the app reads the edits back & leaves untouched text byte for byte as it found it.
4. **Any org file.** The app reads & writes every org file in the folders that the user picks (section 3), in any layout, & edits any heading therein.

## 2. Interface styles

### B. Agenda-first planner, the zoomable agenda (recommended)

One home screen: a timeline that zooms through four levels, Agenda, Day, Week, & Month. A row of chips picks the level, & a pinch steps it: pinch in from Agenda to Day, Week, & Month, & spread out to return. The date in view stays centered across a change of level. A floating button opens a bottom-sheet form for a new record; a tap on a record opens the same sheet to edit it.

```
┌──────────────────────────────────┐
│ ≡  Today            ⌕   ⋮        │
│ (Agenda)(Day)(Week)(Month)        │
├──────────────────────────────────┤
│ MON 23                            │
│ ┃ 09:00 Compressor PM             │
│ ┃ 13:30 Panel walkdown            │
│ TUE 24                            │
│ ┃ Submittal due        DEADLINE   │
│                                   │
│ ┌──────────────────────────────┐  │
│ │ New: [title.............]    │  │
│ │ Date [Nov 24] Time [none]    │  │
│ │ Tags [work] State [TODO]  ✓  │  │
│ └──────────────────────────────┘  │
│                              (+)  │
└──────────────────────────────────┘
```

* **Agenda**: a day-by-day list of events, scheduled & due tasks, & open tasks, as org's agenda shows them. **Saved filters** make it dynamic: each filter is a named set of TODO states, tags, categories, & a date span, like an entry in `org-agenda-custom-commands`. The overflow menu picks the filter. The starting filters mirror the user's two agenda commands: GTD Dashboard (today's agenda, the `inbox` tag, NEXT, WAITING, & stuck projects per `org-stuck-projects`) & Contexts (NEXT entries grouped by tag).
* **Day**: an hour grid of one day, w/ untimed records in a band at the top.
* **Week**: seven day columns on one hour grid.
* **Month**: a grid of weeks; a tap on a day zooms to Day.
* **Bottom sheet**: the one form for capture & edit, built from Material pickers. Its fields follow the entry (section 3): title, state, priority, start (`SCHEDULED`), due (`DEADLINE`), tags as chips, body, & the file & heading that hold it; an entry edited as an event adds date, time span, repeat, & alarm. A drag up expands the sheet to full height for a long body.

Fit to org: close to Orgzly & beorg, w/ a fresher look. Stack: Jetpack Compose & Material 3 (AndroidX).

### A. Palm Suite style

Three apps in one, as on the Palm: Date Book, To Do List, & Memo Pad, each w/ its own views & Palm forms, built on palm-suite's `:palm-ui` & `Recur`. Strong on reuse; it keeps the calendar & the to-do list on separate screens.

### C. Outline browser w/ forms

Files & headings shown as a collapsible tree; a tap on a heading opens a form of its title, state, priority, tags, timestamps, & properties. The most faithful to the file, & the weakest fit for a viewing-first app, since the tree puts structure ahead of dates.

### Comparison

|               | A. Palm Suite                        | B. Agenda-first               | C. Outline               |
|---------------|--------------------------------------|-------------------------------|--------------------------|
| Viewing first | Strong: four calendar views & agenda | Strong: one zoomable timeline | Weak: a tree             |
| Form editing  | Palm forms, already built            | New bottom sheets             | Generic per-heading form |
| Org fidelity  | Records of three kinds               | Every heading, by date        | Every heading            |
| Code reuse    | `:palm-ui`, `Recur`, the views       | None                          | None                     |
| Stack         | Kotlin, plain Views                  | Kotlin, Compose, Material 3   | Either                   |

**Recommendation: B.** One timeline puts events & tasks on the same dates at every zoom level, which serves viewing first better than three separate apps; Material pickers & bottom sheets give form editing w/o Palm's fixed screen size. The cost: no palm-suite code carries over.

## 3. Files & entries

### Folders

The user picks one or more folders through the Storage Access Framework; the app keeps each as a tree URI w/ a persisted permission (`takePersistableUriPermission`). Any provider serves: Termux exposes its home through its DocumentsProvider in the system picker, so a git checkout under `~/org` can be picked directly. The app reads every file in each folder whose name ends in `.org`, skipping dot-files. A folder reads flat, as `org-agenda-files` reads a directory; a switch per folder adds its subfolders, recursively, skipping dot-folders (`.git` & the like). All files are UTF-8; the app keeps the line endings that it finds (LF for a file that it creates).

The app needs no fixed layout & writes no IDs. It creates a file only when the user asks (SAF `createDocument`).

`org-core` owns its record model: one `Entry` per heading, read from it & written back to it, & one `OrgFile` per file (its path & its parsed document). No Palm record type takes part.

### Emacs settings

A file carries few of the settings that govern how Org reads & writes it; the rest live in the user's Emacs init file. The app keeps a settings page that mirrors each Emacs variable below, & `org-core` reads & writes under those values (`OrgSettings`). A line in the file wins where Org lets one win: `#+TODO:` & the like over `org-todo-keywords`, & `#+STARTUP:` (`logdone`, `lognotedone`, `nologdone`, `logrepeat`, `nologrepeat`, `logreschedule`, `nologreschedule`, `logredeadline`, `nologredeadline`, `logdrawer`, & `nologdrawer`) over the log variables. Each starts at the user's current value.

| Variable                                      | Starting value                                          |
|-----------------------------------------------|---------------------------------------------------------|
| `org-todo-keywords`                           | `TODO(t) NEXT(n) WAITING(w@/!) \| DONE(d!) BELAYED(b@)` |
| `org-tags-column`                             | 0                                                       |
| `org-tags-exclude-from-inheritance`           | `undertaking`                                           |
| `org-log-done`                                | `time`                                                  |
| `org-log-repeat`                              | `time`                                                  |
| `org-log-reschedule`                          | `time`                                                  |
| `org-log-redeadline`                          | `time`                                                  |
| `org-log-into-drawer`                         | nil                                                     |
| `org-log-state-notes-insert-after-drawers`    | t                                                       |
| `org-reverse-note-order` (refile position)    | t: a refiled entry goes first under its target          |
| `:prepend` of the capture template            | t: a captured entry goes first under its target         |
| `org-enforce-todo-dependencies`               | t                                                       |
| `org-enforce-todo-checkbox-dependencies`      | t                                                       |
| `org-priority-highest`, `-lowest`, `-default` | 1, 5, 5                                                 |
| `org-agenda-files` (subfolders per folder)    | off, as `~/org` reads flat                              |

The fixtures run under the starting values above; a case that tests another value of one variable sets that variable alone.

### Entries

Every heading, at any level & in any file, is an `Entry`, & every entry is editable through the one bottom-sheet form.

| `Entry` field | Org form                                                                                               |
|---------------|--------------------------------------------------------------------------------------------------------|
| file          | the `.org` file that holds the heading                                                                 |
| outline path  | the titles of the ancestor headings, outermost first                                                   |
| level         | the count of stars                                                                                     |
| state         | the TODO keyword, one of the file's keywords; a keyword after the `\|` is a done state                 |
| priority      | the cookie, `[#A]` to `[#C]` or the range of the file's `#+PRIORITIES:`; absent for none               |
| title         | heading text after the keyword & cookie                                                                |
| tags          | the heading's own tags; the inherited tags are kept apart (below)                                      |
| scheduled     | `SCHEDULED:`                                                                                           |
| deadline      | `DEADLINE:`                                                                                            |
| closed        | `CLOSED: [...]`, set on entry to a done state & removed on leaving it                                  |
| properties    | the `:PROPERTIES:` drawer; `:APPT_WARNTIME:` gives the alarm in minutes, absent for no alarm           |
| body          | the section text after the planning line & the drawers, up to the first child heading, as Org holds it |
| category      | the inherited `:CATEGORY:` (below)                                                                     |

**Inheritance** follows Org. Tags inherit as `org-use-tag-inheritance` `t` gives them: an entry's inherited tags are the file's `#+FILETAGS:` & the tags of its ancestors, in that order, w/o duplicates, less the tags of `org-tags-exclude-from-inheritance`. The category is `:CATEGORY:` of the entry or its nearest ancestor that has one, else the file's last `#+CATEGORY:` line, else the file name w/o `.org`, as `org-get-category` gives it.

**Agenda inclusion** follows Org's agenda: an entry shows on a date through its `SCHEDULED:`, its `DEADLINE:`, & any active timestamp in its own text (heading or body, but not its children's). A date range (`<...>--<...>`) shows on every day from its start to its end. A diary sexp shows on the dates it gives, for the forms `diary-anniversary`, `diary-block`, `diary-cyclic`, `diary-date`, & `diary-float`; any other sexp shows on no date, as a sexp that Org cannot evaluate shows on none. Every open TODO entry shows in the todo list, dated or not. Entries under a `COMMENT` heading or a heading tagged `:ARCHIVE:` show nowhere in the agenda, as `org-agenda-skip-comment-trees` & `org-agenda-skip-archived-trees` give it.

Three layouts serve as examples of files the app reads well; none is required.

```org
#+TODO: TODO NEXT WAITING | DONE CANCELED
#+CATEGORY: Plant

* Compressor PM, Plant 2
:PROPERTIES:
:APPT_WARNTIME: 15
:END:
<2026-11-23 Mon 09:00-10:00>
Bring the oil analysis kit.

* Work
:PROPERTIES:
:CATEGORY: Work
:END:
** TODO [#A] Order VFD spares                                          :site:
DEADLINE: <2026-11-25 Wed> SCHEDULED: <2026-11-23 Mon>
Two ACS580s, frame R3.
** Frick rep contact
Ask about the RWF II oil pump seal kit.
```

A calendar file of level-1 events, a todo file of tasks under category headings, & a memo file of notes under category headings all read as entries of this one kind.

### Events

Org has no event record: an appointment is an entry w/ an active timestamp in its text, & the parse stays Org's. The form reads the leading stamp lines of an entry as an event: the lines at the start of its body that each hold one active timestamp & naught else. When those lines match the shapes below, the form adds date, time span, repeat, weekday set, & alarm, & shows the rest of the body as text; a save rewrites those lines alone. A timestamp w/o a time (`<2026-11-26 Thu>`) makes an untimed event.

**Repeats** use org repeaters alone: every N days as `+Nd`, every N weeks as `+Nw`, every N months on the same date as `+Nm`, & every N years as `+Ny`. Example: `<2026-11-23 Mon 09:00-10:00 +2w>`. The `.+` & `++` marks stay in the file as written until the user changes the repeat. The app expands a repeat into dates as Org's agenda does, incl. a monthly repeat from the 29th to the 31st; a test compares those dates against Emacs. As in Org, such a repeat runs over into the next month w/o clamping: `<2026-01-31 Sat +1m>` falls on Jan 31, Mar 3, Mar 31, & May 1. A plain timestamp repeats only when the repeater is its last part; `<... +1m -2d>` shows on its first date alone.

A weekly repeat on several weekdays puts one timestamp per weekday in the heading, one per line, each w/ the same time & repeater. The first timestamp is the event's first date; the rest fall within the six days after it. Example, Monday & Thursday every other week:

```org
* Plant 2 walkdown
<2026-11-23 Mon 07:00-08:00 +2w>
<2026-11-26 Thu 07:00-08:00 +2w>
```

On read, timestamps of that shape give one event whose weekday set names their days. The repeat form offers what org repeaters express & naught else: no nth weekday of the month, no end date, & no deletion of one occurrence. An edit to a repeating event changes every occurrence.

**Other shapes.** Leading stamp lines that match none of the shapes above give the form no event fields, & the lines stay in the body text, editable as text: a diary sexp (`<%%(...)>`), a date range (`<...>--<...>`), an hourly repeater, a habit repeater (`.+1d/3d`), several timestamps w/o a weekly repeater, or several timestamps that differ in time, repeater, warning, or week. A line w/ text after its stamp, or an inactive stamp, is no stamp line; it is body text, as in Org. A planning line that holds a timestamp the app cannot read locks the field of that keyword (section 4); the rest of the entry stays editable.

### States, priorities, & repeating tasks

**Priorities** come from the priority settings (section 3, Emacs settings): letters or numbers, highest, lowest, & default; the app writes no `#+PRIORITIES:` line. A heading w/o a cookie shows the default, & the app writes no cookie for it until the user changes the priority; a change to the default value writes its cookie, as `org-priority` writes it. The first `#+PRIORITIES:` line that the user adds in Emacs, w/ its three values, governs the range, the default, & the form's pop-up list, letters or numbers, as `org-collect-keywords` reads it. A number too large to read gives the default range in its place.

**States** come from the file's `#+TODO:`, `#+SEQ_TODO:`, & `#+TYP_TODO:` lines, merged as Org merges them, or from `org-todo-keywords` (section 3, Emacs settings) w/o any. The form shows them in a pop-up list; a done state draws a checked box in the views. Each change logs as Org logs it under the settings: `CLOSED:` on entry to a done state per `org-log-done` (removed on a return to an open state while any logging is on, viz. `org-log-done` or a keyword's mark, & kept otherwise, as Org keeps it), & a state line or a note per the keyword's own marks (`!` for a time, `@` for a note, `/` for the mark on leaving), placed per `org-log-into-drawer` & `org-log-state-notes-insert-after-drawers`. A state that asks for a note opens a note field in the form; an empty note logs the line alone, as Org does. A done state that `org-enforce-todo-dependencies` or `org-enforce-todo-checkbox-dependencies` forbids (an open child, or an unchecked box) refuses the save w/ the reason. A changed date logs a `Rescheduled from` or `New deadline from` line per `org-log-reschedule` & `org-log-redeadline`. One save that changes the state & a date logs both, as the two Org commands would. A new `SCHEDULED:` or `DEADLINE:` also removes `CLOSED:`, as `org-schedule` & `org-deadline` do, & a newly set planning keyword goes first on its line. The removal of a date removes every `KEY: <...>` of that keyword in the entry, body included, as `org-remove-timestamp-with-keyword` does.

**Repeating tasks.** An entry whose text holds an active repeating timestamp (heading, planning line, or body; the first such stamp decides, as `org-get-repeat` finds it, & a zero count repeats naught) does on entry to a done state what `org-auto-repeat-maybe` does: each repeating timestamp moves on by its repeater (`+` from the old date, `.+` from today, or from now for an hourly repeat, `++` to the first future date), the state returns to the head of its sequence (a `#+TYP_TODO:` state stays, & a `:REPEAT_TO_STATE:` property wins), a `SCHEDULED:` w/o a repeater is removed, a `--N` delay is dropped, as `org-timestamp-change` drops it, & the app writes `:LAST_REPEAT:` & the state-change log line as Org writes them under `org-log-repeat`, w/ no `CLOSED:`.

### Capture & refile

Settings hold a default capture file & heading, starting at `inbox.org` w/o a heading, as the user's capture templates target it. A new entry goes under that heading (or at the top level of the file when the setting names no heading), last or first per the capture position setting, as a capture template's `:prepend` places it; the sheet offers a file & heading picker to send it elsewhere. A missing default file or heading sends the user to the picker; the app creates neither unasked.

A move to another heading goes as `org-refile` does: the entry's subtree goes to the end of the target's subtree, or to its start (right after the target's own section, or, w/o a target, before the file's first heading, save that a heading on the file's first line is passed over & the entry goes before the second, as `org-refile` places it) per the refile position setting, as `org-reverse-note-order` places it, its stars shifted to one level below the target; the text stays as it was, save that each shifted heading's tags re-align, as Org re-aligns them. Within one file the app writes once. Across files it writes the target file first, then the source, so that a failure leaves a duplicate & never loses the entry.

## 4. Round-trip rules

1. **Lossless parse.** `org-core` parses each file into a tree of headings whose every node keeps its exact source span. Text the app does not model (other properties, drawers, logbook lines, planning keywords it ignores, & child headings) stays in the span untouched.
2. **Span writes.** A save replaces only the changed heading's span, & within it only the lines that the edited fields own. A new entry goes last or first under its parent per the capture position (section 3), w/ a blank line before it when the parent heading has one (or, for a top-level entry, the last top-level heading), as `org-blank-before-new-entry` `auto` does, & the blank lines around the insertion point trimmed as `org-insert-heading` & `org-capture` trim them (a first-place capture drops the blank lines before its insertion point); a delete removes its subtree whole.
3. **Read, edit, write.** Each save re-reads the file, finds the heading by its anchor, applies the edit, & writes at once. The anchor is the heading line & the section text as the app last read them, w/ the heading's line number. The heading at the same line number w/ the same text is the target; failing that, the one heading anywhere in the file w/ the same heading line & section text, provided the heading had no twin when the app read the file. A heading changed or deleted meanwhile, or moved when it has a twin, refuses the save w/ a conflict; the form keeps the edit for a retry after the app re-reads the file.
4. **Read-only fallback.** A planning field whose timestamp the app cannot read (section 3) shows in its views w/ a lock marker, & the form shows it read-only w/ a note to edit it in Emacs.
5. **No reformatting.** The app never re-indents, reorders properties, or rewrites a timestamp whose value is unchanged. It re-aligns the tags of a heading line that it rewrites, as Org does under `org-tags-column` (section 3, Emacs settings), & of no other line. A new property drawer goes right after the planning line, as Org puts it.
6. **Writes in one stream.** The app opens the document w/ mode `"wt"` so the provider truncates the old bytes, & writes the whole file in one stream.

## 5. Sync behavior

* The user syncs the folders w/ git in Termux: a pull, a string of edits in the app, & a push. Syncthing or the like serves as well, since the app holds no state that a sync could break.
* On start & on each return to the front, the app walks the folders & re-reads every file whose size or last-modified time differs from the one it last read, & drops files that are gone.
* Every save writes at once (section 4 rule 3); no edit waits in the app for a later write.

## 6. Stack & repository

* Kotlin, Jetpack Compose, & Material 3 (AndroidX), minSdk 26, JDK 17.
* One standalone Gradle build in this repository, w/ no tie to palm-suite.
* `:org-core`: a Kotlin JVM module w/ no runtime dependency, holding the parser, the entry model, the writer, & the anchor that finds an edited heading in fresh text. JUnit 4 tests round-trip fixture files: parse, write unchanged, & compare bytes; then edit each field & compare against a fixture that Emacs produced for the same edit. `tools/make-fixtures.el` makes those fixtures through Org's own commands.
* `:app`: the Android app, added in phase 2.
* CI (GitHub Actions) runs `gradle :org-core:test` on JDK 17 & Gradle 9.4.1.

## 7. Build phases

1. `org-core` parse & write w/ round-trip tests, incl. the weekday timestamp sets & the repeats on the 29th to 31st of a month.
	* 1b. `org-core` generalized to any org file: an `Entry` per heading, tag & category inheritance, anchors in place of IDs & the rebase, refile, the Emacs settings, & the agenda dates as Org gives them.
2. The folder list & the folder walk, & the Agenda zoom level, read-only.
3. The Day, Week, & Month zoom levels, w/ the chips & the pinch.
4. Bottom-sheet forms for capture & edit, w/ the read, edit, & write of section 4 rule 3, & the capture settings.
5. Saved agenda filters.
6. Alarms from `:APPT_WARNTIME:`.
7. The outline browser: any file's headings as a tree, & the file & heading picker for capture & refile.
