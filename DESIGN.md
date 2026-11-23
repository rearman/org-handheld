# org-palm: design

An Android front end for org-mode data. The phone shows calendars & a dynamic agenda first, edits records through forms w/o Emacs keystrokes, & keeps its files in a form that Emacs opens, edits, & hands back.

## 1. Goals & limits

1. **Viewing first.** The home screen is a timeline that shows the agenda & the calendar; editing is a step taken from it.
2. **Form editing.** Every edit goes through a form w/ labeled fields, pop-up lists, & date pickers. The user never types org syntax on the phone.
3. **Emacs round trip.** The files live in a shared folder (Syncthing or the like). Emacs may edit any of them; the app reads the edits back & leaves untouched text byte for byte as it found it.
4. **App-owned files only.** The app reads & writes the three files that it creates (section 3). It opens no other org file in the folder. Emacs edits inside those three files count, so long as they keep the layout.

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

* **Agenda**: a day-by-day list of events, scheduled & due tasks, & open tasks w/o a date, as org's agenda shows them. **Saved filters** make it dynamic: each filter is a named set of TODO states, tags, categories, & a date span, like an entry in `org-agenda-custom-commands`. The overflow menu picks the filter.
* **Day**: an hour grid of one day, w/ untimed records in a band at the top.
* **Week**: seven day columns on one hour grid.
* **Month**: a grid of weeks; a tap on a day zooms to Day.
* **Bottom sheet**: the one form for capture & edit, built from Material pickers. Its fields follow the record (section 3): title, date, time span, repeat, alarm, & body for an event; title, state, priority, start (`SCHEDULED`), due (`DEADLINE`), category, & body for a task. Tags are chips on both. A drag up expands the sheet to full height for a long body.

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
| Org fidelity  | Records of three kinds               | Records of three kinds        | Every heading            |
| Code reuse    | `:palm-ui`, `Recur`, the views       | None                          | None                     |
| Stack         | Kotlin, plain Views                  | Kotlin, Compose, Material 3   | Either                   |

**Recommendation: B.** One timeline puts events & tasks on the same dates at every zoom level, which serves viewing first better than three separate apps; Material pickers & bottom sheets give form editing w/o Palm's fixed screen size. The cost: no palm-suite code carries over.

## 3. File layout

The user picks one folder through the Storage Access Framework. The app creates three files therein. All three are UTF-8; the app keeps the line endings that it finds (LF for a file that it creates).

Every record heading carries an `:ID:` property in its `:PROPERTIES:` drawer, a UUID in the form that `org-id` writes (`org-id-method` `uuid`). A heading that Emacs adds w/o an ID gets one on the app's next write to that file. Category headings carry IDs too.

`org-core` owns its record model: `Event`, `Task`, `Note`, & `Category`, each read from a heading & written back to it. No Palm record type takes part.

### calendar.org: events

One level-1 heading per event.

```org
* Compressor PM, Plant 2
:PROPERTIES:
:ID:       6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10
:APPT_WARNTIME: 15
:END:
<2026-11-23 Mon 09:00-10:00>
Bring the oil analysis kit.

* Office closed                                                     :private:
:PROPERTIES:
:ID:       0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88
:END:
<2026-11-26 Thu>
```

| `Event` field | Org form                                                                             |
|---------------|--------------------------------------------------------------------------------------|
| id            | `:ID:`                                                                               |
| title         | heading text                                                                         |
| timestamps    | the timestamp lines after the drawer: `<2026-11-23 Mon 09:00-10:00>`                 |
| repeater      | the repeater of the timestamps, as written: `+2w`, `.+1d`, or `++1m`                 |
| warn minutes  | `:APPT_WARNTIME:` in minutes; absent for no alarm                                    |
| tags          | heading tags; `:private:` marks a private event                                      |
| body          | body text after the timestamp lines                                                  |

A timestamp w/o a time (`<2026-11-26 Thu>`) makes an untimed event.

**Repeats** use org repeaters alone: every N days as `+Nd`, every N weeks as `+Nw`, every N months on the same date as `+Nm`, & every N years as `+Ny`. Example: `<2026-11-23 Mon 09:00-10:00 +2w>`. The `.+` & `++` marks stay in the file as written until the user changes the repeat. The app expands a repeat into dates as Org's agenda does, incl. a monthly repeat from the 29th to the 31st; a test compares those dates against Emacs. As in Org, such a repeat runs over into the next month w/o clamping: `<2026-01-31 Sat +1m>` falls on Jan 31, Mar 3, Mar 31, & May 1. A plain timestamp repeats only when the repeater is its last part; `<... +1m -2d>` shows on its first date alone.

A weekly repeat on several weekdays puts one timestamp per weekday in the heading, one per line, each w/ the same time & repeater. The first timestamp is the event's first date; the rest fall within the six days after it. Example, Monday & Thursday every other week:

```org
* Plant 2 walkdown
:PROPERTIES:
:ID:       9a4c0e1f-3b27-4d8e-b5a1-6c2f8e7d0b43
:END:
<2026-11-23 Mon 07:00-08:00 +2w>
<2026-11-26 Thu 07:00-08:00 +2w>
```

On read, timestamps of that shape give one `Event` whose weekday set names their days. The repeat form offers what org repeaters express & naught else: no nth weekday of the month, no end date, & no deletion of one occurrence. An edit to a repeating event changes every occurrence.

**What the app cannot read.** A heading whose timestamps match none of the shapes above shows as a read-only record (section 4): a diary sexp (`<%%(...)>`), a date range (`<...>--<...>`), two timestamps on one line, text after a timestamp, an inactive timestamp among the timestamp lines, an hourly repeater, a habit repeater (`.+1d/3d`), a timestamp in the title, several timestamps w/o a weekly repeater, or several timestamps that differ in time, repeater, warning, or week. A task whose planning line holds a timestamp the app cannot read is read-only too.

### todo.org: tasks

```org
#+TODO: TODO NEXT WAITING | DONE CANCELED

* Work
:PROPERTIES:
:ID:       c3e8b1a0-5d47-4f2a-9e6b-1a7c0d3f8e52
:CATEGORY: Work
:END:
** TODO [#A] Order VFD spares                                       :private:
DEADLINE: <2026-11-25 Wed> SCHEDULED: <2026-11-23 Mon>
:PROPERTIES:
:ID:       5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37
:END:
Two ACS580s, frame R3.
** DONE [#C] Update I/O list
CLOSED: [2026-11-20 Fri 16:42]
:PROPERTIES:
:ID:       7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04
:END:

* Unfiled
:PROPERTIES:
:ID:       e1f6c9b3-2a85-4d07-8b4c-0d3e7a5f1b96
:CATEGORY: Unfiled
:END:
```

| `Task` field | Org form                                                                                      |
|--------------|-----------------------------------------------------------------------------------------------|
| id           | `:ID:`                                                                                        |
| category     | the level-1 heading, w/ `:CATEGORY:` (section "Categories")                                   |
| state        | the TODO keyword, one of `#+TODO:`; a keyword after the `\|` is a done state                  |
| priority     | the cookie, `[#A]` to `[#C]` or the range of the file's `#+PRIORITIES:`; absent for none      |
| title        | level-2 heading text after the keyword & cookie                                               |
| scheduled    | `SCHEDULED:`                                                                                  |
| deadline     | `DEADLINE:`                                                                                   |
| closed       | `CLOSED: [...]`, set on entry to a done state & removed on leaving it                         |
| tags         | heading tags; `:private:` marks a private task                                                |
| body         | body text                                                                                     |

**Priorities** are Org's default letters, `A` to `C`, w/ `B` the default; the app writes no `#+PRIORITIES:` line. A heading w/o a cookie shows the default, & the app writes no cookie for it until the user changes the priority. The first `#+PRIORITIES:` line that the user adds in Emacs, w/ its three values, governs the range, the default, & the form's pop-up list, letters or numbers, as `org-collect-keywords` reads it. A number too large to read gives the default range in its place.

**States** come from the file's `#+TODO:`, `#+SEQ_TODO:`, & `#+TYP_TODO:` lines, merged as Org merges them, or `TODO | DONE` w/o any. The form shows them in a pop-up list; a done state draws a checked box in the views. On entry to a done state the app writes `CLOSED:` as `org-log-done` `time` writes it; a return to an open state removes it, as Org does. A new `SCHEDULED:` or `DEADLINE:` also removes `CLOSED:`, as `org-schedule` & `org-deadline` do, & a newly set planning keyword goes first on its line. The removal of a date acts on the planning line alone; a `DEADLINE:` written in the body stays.

**Repeating tasks.** A task whose entry holds an active repeating timestamp (heading, planning line, or body; the first such stamp decides, as `org-get-repeat` finds it, & a zero count repeats naught) does on entry to a done state what `org-auto-repeat-maybe` does: each repeating timestamp moves on by its repeater (`+` from the old date, `.+` from today, or from now for an hourly repeat, `++` to the first future date), the state returns to the head of its sequence (a `#+TYP_TODO:` state stays, & a `:REPEAT_TO_STATE:` property wins), a `SCHEDULED:` w/o a repeater is removed, a `--N` delay is dropped, as `org-timestamp-change` drops it, & the app writes `:LAST_REPEAT:` & the state-change log line as Org writes them, w/ no `CLOSED:`.

### memos.org: notes

memos.org holds free-text notes, filed by category. A notes screen (phase 7) serves it; the app reads & keeps it from phase 1.

```org
* Work
:PROPERTIES:
:ID:       4b9e2c7a-1d06-4f83-a5b2-9e0c3d7f6a18
:CATEGORY: Work
:END:
** Frick rep contact
:PROPERTIES:
:ID:       a2d5f8c1-6e39-4b07-9c4a-3f1b7e0d2c65
:END:
Ask about the RWF II oil pump seal kit.

* Unfiled
:PROPERTIES:
:ID:       d8a3e6f0-7b12-4c95-8e4d-5a0f2b9c1e73
:CATEGORY: Unfiled
:END:
```

| `Note` field | Org form                                                    |
|--------------|-------------------------------------------------------------|
| id           | `:ID:`                                                      |
| category     | the level-1 heading, w/ `:CATEGORY:` (section "Categories") |
| title        | level-2 heading text                                        |
| body         | body text; file order within the category is the note order |

### Categories

todo.org & memos.org share one category layout. Each category is a level-1 heading w/ an `:ID:` & a `:CATEGORY:` property; the `Category` record holds the id & the name. On read, the heading text names the category; a rename writes both the heading text & `:CATEGORY:`, so that Org's agenda shows the same name. The heading named Unfiled holds the records w/o a category; the app creates it, or any category heading it lacks, when it first files a record therein. A change of category moves the record's span, ID & text unchanged, to the end of the new category, as `org-refile` does.

## 4. Round-trip rules

1. **Lossless parse.** `org-core` parses each file into a tree of headings whose every node keeps its exact source span. Text the app does not model (other properties, drawers, logbook lines, planning keywords it ignores, & child headings) stays in the span untouched.
2. **Span writes.** A save replaces only the changed heading's span, & within it only the lines that the edited fields own. A new record goes at the end of its parent, w/ a blank line before it when the parent heading has one (or, for a top-level record, the last top-level heading), as `org-blank-before-new-entry` `auto` does; a delete removes its span whole. Every write also adds a missing `:ID:` (section 3).
3. **Hash check ere each write.** The app keeps the hash of the bytes that it last read. Ere writing, it re-reads the file; on a mismatch it re-parses, finds the edited record by `:ID:`, reapplies the edit to the new tree, & writes that. A record that Emacs made read-only meanwhile refuses the edit, as rule 4 says. A record that Emacs deleted meanwhile is lost to the edit; the app reports this in a toast & keeps the edit in its undo buffer.
4. **Read-only fallback.** A heading whose timestamps the app cannot read (section 3) shows in its views w/ a lock marker. Its form opens read-only w/ a note to edit it in Emacs.
5. **No reformatting.** The app never re-indents, reorders properties, or rewrites a timestamp whose value is unchanged. It re-aligns the tags of a heading line that it rewrites, as Org does, & of no other line. New lines follow Org's defaults: tags right-aligned to column 77 (`org-tags-column` -77), & the property drawer right after the planning line.
6. **Escaping.** Text from a form must never parse as structure. The writer applies these rules, & the parser undoes the first three:
	* A title or memo first line that ends in `:word:` gets a trailing zero-width space (U+200B) after the last colon, so Org reads no tags.
	* A title that begins w/ a TODO keyword of the file, `COMMENT`, or a priority cookie (`[#A]`) gets a leading zero-width space.
	* A body line that would read as structure gets Org's own escape: a leading comma, as `org-escape-code-in-region` writes it in a source block. Such lines are a heading (one or more `*` & a space), a drawer line (`:PROPERTIES:`, `:END:`, or any `:NAME:`), a keyword line (`#+TODO:` & the like, which would change the file's settings), a planning line (`DEADLINE:`, `SCHEDULED:`, `CLOSED:`), & in an event, a line that begins w/ a timestamp, lest it read as another date. The parser strips one leading comma from such lines.
	* A memo whose first line is blank takes a heading of `(untitled)`, & its whole text goes into the body.
7. **Writes in one stream.** The app opens the document w/ mode `"wt"` so the provider truncates the old bytes, & writes the whole file in one stream.

## 5. Sync behavior

* The folder is a SAF tree URI w/ a persisted permission (`takePersistableUriPermission`), chosen once at first run.
* The app reloads a file on resume, & on a pull of the list, whenever the file's last-modified time differs from the one it last read.
* Syncthing conflict copies (`calendar.sync-conflict-20261123-091500-ABCDEF7.org` & the like) appear in a banner atop the views that use the file. The app neither reads nor merges them; the user resolves them in Emacs.

## 6. Stack & repository

* Kotlin, Jetpack Compose, & Material 3 (AndroidX), minSdk 26, JDK 17.
* One standalone Gradle build in this repository, w/ no tie to palm-suite.
* `:org-core`: a Kotlin JVM module w/ no runtime dependency, holding the parser, the record model, the writer, & the rebase of an edit onto fresh text. JUnit 4 tests round-trip fixture files: parse, write unchanged, & compare bytes; then edit each field & compare against a fixture that Emacs produced for the same edit. `tools/make-fixtures.el` makes those fixtures through Org's own commands.
* `:app`: the Android app, added in phase 2.
* CI (GitHub Actions) runs `gradle :org-core:test` on JDK 17 & Gradle 9.4.1.

## 7. Build phases

1. `org-core` parse & write w/ round-trip tests, incl. the weekday timestamp sets & the repeats on the 29th to 31st of a month.
2. The Agenda zoom level, read-only.
3. The Day, Week, & Month zoom levels, w/ the chips & the pinch.
4. Bottom-sheet forms for capture & edit, w/ the hash-checked write.
5. Saved agenda filters.
6. Alarms from `:APPT_WARNTIME:`.
7. The notes screen for memos.org.

## 8. Open questions

1. Whether an address book comes along, & in what file form (one heading per contact w/ properties, as `org-contacts` keeps them, is the likely choice).
