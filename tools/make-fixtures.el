;;; make-fixtures.el --- Make the Emacs-made fixtures of org-core  -*- lexical-binding: t; -*-

;; Run from the repository root:
;;
;;	emacs --batch -l tools/make-fixtures.el
;;
;; For each field-edit case it reads an input fixture from
;; org-core/src/test/resources/, applies the edit through Org's own
;; commands, and writes <case>.expected.org beside it. It also writes
;; repeats.txt, the dates on which Org's agenda shows each repeating
;; timestamp of a list, and inheritance.txt, the category, the tags
;; (w/ inheritance), and the outline path that Org gives each heading of
;; a few inputs, and agenda.txt, the dates of date ranges and diary sexps,
;; and due.txt, the days on which a missed SCHEDULED or DEADLINE item, or
;; a deadline ahead, shows.
;; The Kotlin tests compare org-core against all of them.
;;
;; Every case runs under the user's settings; a case that tests another
;; value of one variable let-binds that variable alone. The clock, the
;; locale, and the Org settings are fixed, and each file
;; keeps the line endings it was read with, so a second run gives no
;; diff.

(require 'cl-lib)
(require 'org)
(require 'org-agenda)
(require 'org-capture)

(defconst mf-dir (expand-file-name "org-core/src/test/resources/" default-directory)
	"The fixture folder.")

(defconst mf-now (encode-time (list 0 30 14 23 11 2026 nil -1 nil))
	"The fixed clock: 2026-11-23 14:30 local time.")

;; The user's settings (DESIGN.md section 3, Emacs settings), as init.el sets
;; them; a case that tests another value let-binds that variable alone.
;; Then Org's defaults where the design names them, and English day names. No
;; refile bookmark, so that a run leaves the user's bookmark file alone.
(setq org-todo-keywords '((sequence "TODO(t)" "NEXT(n)" "WAITING(w@/!)" "|" "DONE(d!)" "BELAYED(b@)"))
	org-tags-column 0
	org-priority-highest 1
	org-priority-lowest 5
	org-priority-default 5
	org-tags-exclude-from-inheritance '("undertaking")
	org-log-done 'time
	org-log-repeat 'time
	org-log-reschedule 'time
	org-log-redeadline 'time
	org-log-into-drawer nil
	org-log-state-notes-insert-after-drawers t
	org-reverse-note-order t
	org-enforce-todo-dependencies t
	org-enforce-todo-checkbox-dependencies t
	org-deadline-warning-days 14
	org-auto-align-tags t
	org-adapt-indentation nil
	org-closed-keep-when-no-todo nil
	org-agenda-skip-timestamp-if-done nil
	org-agenda-show-future-repeats t
	org-agenda-prefer-last-repeat nil
	org-bookmark-names-plist nil
	system-time-locale "C"
	make-backup-files nil)

(defun mf-goto (id)
	"Move point to the heading whose :ID: is ID."
	(goto-char (point-min))
	(unless (re-search-forward (concat "^:ID:[ \t]+" (regexp-quote id) "[ \t]*$") nil t)
		(error "No heading with ID %s" id))
	(org-back-to-heading t))

(defun mf-goto-heading (text)
	"Move point to the first heading line that begins w/ TEXT."
	(goto-char (point-min))
	(unless (re-search-forward (concat "^" (regexp-quote text)) nil t)
		(error "No heading %s" text))
	(org-back-to-heading t))

(defmacro mf-with-fixed-clock (&rest body)
	"Run BODY w/ Org's clock fixed at `mf-now'.
Native-compiled Org calls the primitive `current-time' directly, so the
Lisp functions that read the clock are replaced as well."
	`(cl-letf (((symbol-function 'current-time) (lambda () mf-now))
						 ((symbol-function 'org-current-time) (lambda (&rest _) mf-now))
						 ((symbol-function 'org-current-effective-time) (lambda (&rest _) mf-now))
						 ((symbol-function 'org-today) (lambda () (time-to-days mf-now))))
		 ,@body))

(defun mf-visit (input name)
	"Visit a temporary copy of INPUT named after NAME.
Return the buffer and the coding system to write it w/. Emacs detects the
coding system and line endings as it does for any file (a file of mixed
line endings reads as LF w/ the carriage returns kept as text), and the
output keeps them."
	(let ((tmp (expand-file-name (concat "mf-" name ".org") temporary-file-directory)))
		(copy-file (expand-file-name input mf-dir) tmp t)
		(let ((buf (find-file-noselect tmp)))
			(with-current-buffer buf
				(let ((coding buffer-file-coding-system))
					;; An ASCII file reads as undecided; write it as UTF-8 w/ the same line endings.
					(when (eq (coding-system-base coding) 'undecided)
						(setq coding (coding-system-change-text-conversion coding 'utf-8)))
					;; A file w/o a line ending (an empty one) gets LF on every system.
					(when (or (= (buffer-size) 0) (vectorp (coding-system-eol-type coding)))
						(setq coding (coding-system-change-eol-conversion coding 'unix)))
					(cons buf coding))))))

(defun mf-save (visit name)
	"Write the buffer of VISIT (from `mf-visit') to NAME.expected.org."
	(with-current-buffer (car visit)
		(let ((coding-system-for-write (cdr visit)))
			(write-region nil nil (expand-file-name (concat name ".expected.org") mf-dir) nil 'silent))))

(defun mf-discard (visit)
	"Kill the buffer of VISIT unsaved and delete its temporary file."
	(let* ((buf (car visit))
				 (file (buffer-file-name buf)))
		(with-current-buffer buf (set-buffer-modified-p nil))
		(kill-buffer buf)
		(delete-file file)))

(defun mf-run (fn)
	"Call FN w/ the fixed clock.
A state-change log note, which Org writes from `post-command-hook', is
written ere the file is saved."
	(mf-with-fixed-clock
		(funcall fn)
		(run-hooks 'post-command-hook)))

(defun mf-case (name input fn)
	"Visit a copy of INPUT, call FN at the start of the buffer, and write NAME.expected.org."
	(let ((visit (mf-visit input name)))
		(unwind-protect
				(progn
					(with-current-buffer (car visit)
						(goto-char (point-min))
						(mf-run fn))
					(mf-save visit name))
			(mf-discard visit))))

(defun mf-ts (y m d &optional h min extra inactive)
	"Insert an Org timestamp for Y-M-D, w/ H:MIN when H is non-nil, and EXTRA before the bracket."
	(org-insert-timestamp
	 (encode-time (list 0 (or min 0) (or h 0) d m y nil -1 nil))
	 (and h t) inactive nil nil extra))

(defun mf-timestamp-lines ()
	"Return the start and end of the timestamp lines after the property drawer."
	(org-back-to-heading t)
	(forward-line)
	(when (looking-at-p org-planning-line-re) (forward-line))
	(when (looking-at-p "^:PROPERTIES:") (re-search-forward "^:END:") (forward-line))
	(let ((beg (point)))
		(while (looking-at-p "^<[^>\n]*>[ \t]*$") (forward-line))
		(cons beg (point))))

(defun mf-set-timestamps (writer)
	"Replace the timestamp lines of the current heading by what WRITER inserts."
	(let ((range (mf-timestamp-lines)))
		(delete-region (car range) (cdr range))
		(goto-char (car range))
		(funcall writer)))

(defun mf-set-body (text)
	"Replace the body of the current heading by TEXT, keeping trailing blank lines."
	(org-end-of-meta-data t)
	(let* ((beg (point))
				 (end (save-excursion
								(outline-next-heading)
								(skip-chars-backward " \t\n")
								(max beg (if (bolp) (point) (line-end-position))))))
		(delete-region beg end)
		(goto-char beg)
		(insert text)))

(defun mf-note (text)
	"Finish the pending log note of Org's *Org Note* buffer w/ TEXT."
	(with-current-buffer "*Org Note*"
		(goto-char (point-max))
		(insert text)
		(org-store-log-note)))

;;; States: CLOSED, the state lines and notes of the keywords' marks, and dependencies.

(mf-case "user-done" "user.org"
	(lambda () (mf-goto-heading "** NEXT Order the fan motor") (org-todo "DONE")))
(mf-case "todo-reopen" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-todo "TODO")))
(mf-case "user-waiting-note" "user.org"
	(lambda ()
		(mf-goto-heading "** TODO Pick up the motor")
		(org-todo "WAITING")
		(run-hooks 'post-command-hook)
		(mf-note "Waiting on the vendor quote.")))
(mf-case "user-empty-note" "user.org"
	(lambda ()
		(mf-goto-heading "** TODO Pick up the motor")
		(org-todo "WAITING")
		(run-hooks 'post-command-hook)
		(mf-note "")))
(mf-case "user-leave-waiting" "user.org"
	(lambda () (mf-goto-heading "** WAITING Electrician quote") (org-todo "TODO")))
(let ((org-log-into-drawer t))
	(mf-case "user-drawer" "user.org"
		(lambda () (mf-goto-heading "** TODO Pick up the motor") (org-todo "DONE"))))
(mf-case "startup-drawer" "startup.org"
	(lambda () (mf-goto-heading "* TODO Fix the door") (org-todo "DONE")))
(mf-case "user-blocked" "user.org"
	(lambda () (mf-goto-heading "* Shop renovation") (org-todo "DONE")))
(mf-case "user-blocked-box" "user.org"
	(lambda () (mf-goto-heading "** TODO Hardware store run") (org-todo "DONE")))
(mf-case "crlf-done" "crlf.org"
	(lambda () (mf-goto "b85d1e3a-6c07-4f29-9a4e-0d7f2c8b1e53") (org-todo "DONE")))

;;; Planning: dates set and removed, w/ their log lines.

(mf-case "user-reschedule" "user.org"
	(lambda () (mf-goto-heading "** NEXT Order the fan motor") (org-schedule nil "2026-11-30")))
(mf-case "deadline-set" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-deadline nil "2026-11-27")))
(mf-case "schedule-add" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-schedule nil "2026-11-30")))
(mf-case "schedule-beside-deadline" "children.org"
	(lambda () (mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60") (org-schedule nil "2026-12-02")))
(mf-case "user-unschedule" "user.org"
	(lambda () (mf-goto-heading "** NEXT Order the fan motor") (org-schedule '(4))))
(mf-case "user-undeadline" "user.org"
	(lambda () (mf-goto-heading "** TODO Call the vendor") (org-deadline '(4))))
;; Two commands, each storing its note ere the next: a date, then a state.
(mf-case "user-state-and-date" "user.org"
	(lambda ()
		(mf-goto-heading "** NEXT Order the fan motor")
		(org-schedule nil "2026-11-30")
		(run-hooks 'post-command-hook)
		(org-todo "DONE")))

;;; The heading line: title, priority, and tags.

(mf-case "title-edit" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-edit-headline "Order VFD spares & fuses")))
(mf-case "user-priority-set" "user.org"
	(lambda () (mf-goto-heading "** TODO Pick up the motor") (org-priority 1)))
(mf-case "user-priority-default" "user.org"
	(lambda () (mf-goto-heading "** TODO Pick up the motor") (org-priority 5)))
(mf-case "priority-remove" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-priority 'remove)))
;; Tags that end at column 77, as Org's own org-tags-column puts them.
(let ((org-tags-column -77))
	(mf-case "tags-set" "todo.org"
		(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-set-tags '("work" "plc")))))
(mf-case "tags-clear" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-set-tags nil)))

;;; Properties, the body, and event timestamps.

(mf-case "warn-set" "calendar.org"
	(lambda () (mf-goto "0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88") (org-entry-put nil "APPT_WARNTIME" "30")))
(mf-case "warn-remove" "calendar.org"
	(lambda () (mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10") (org-entry-delete nil "APPT_WARNTIME")))
(mf-case "foreign-warn" "foreign.org"
	(lambda () (mf-goto "e4b7a1c9-0d52-4e8f-b3a6-7c1d9f2e0a85") (org-entry-put nil "APPT_WARNTIME" "20")))
(mf-case "drawer-remove" "children.org"
	(lambda () (mf-goto "5b8c2e7f-9a14-4d63-b0e7-6f3a1c9d2e85") (org-entry-delete nil "ID")))
(mf-case "logbook-body" "logbook.org"
	(lambda () (mf-goto "6a2f9e0b-4d71-4c53-b8e2-1f0c7a9d3e58") (mf-set-body "Use the Fluke 754.\nZero at 0 psig.")))
(mf-case "weekdays-add" "calendar.org"
	(lambda ()
		(mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10")
		(mf-set-timestamps
		 (lambda ()
			 (mf-ts 2026 11 23 9 0 "-10:00 +1w") (insert "\n")
			 (mf-ts 2026 11 26 9 0 "-10:00 +1w") (insert "\n")))))
(mf-case "weekdays-remove" "calendar.org"
	(lambda ()
		(mf-goto "9a4c0e1f-3b27-4d8e-b5a1-6c2f8e7d0b43")
		(mf-set-timestamps (lambda () (mf-ts 2026 11 23 7 0 "-08:00 +2w") (insert "\n")))))
(mf-case "repeater-change" "calendar.org"
	(lambda ()
		(mf-goto "9a4c0e1f-3b27-4d8e-b5a1-6c2f8e7d0b43")
		(mf-set-timestamps
		 (lambda ()
			 (mf-ts 2026 11 23 7 0 "-08:00 +1w") (insert "\n")
			 (mf-ts 2026 11 26 7 0 "-08:00 +1w") (insert "\n")))))

;;; Repeating tasks marked done: org-auto-repeat-maybe shifts the dates.

(mf-case "user-repeat" "user.org"
	(lambda () (mf-goto-heading "** NEXT Weekly oil check") (org-todo "DONE")))
(mf-case "repeat-plus" "repeat.org"
	(lambda () (mf-goto "a1c4e7f0-2b5d-4893-b6e9-c2f5a8d1b4e7") (org-todo "DONE")))
(mf-case "repeat-dot" "repeat.org"
	(lambda () (mf-goto "b2d5f8a1-3c6e-4904-87fa-d3a6b9e2c5f8") (org-todo "DONE")))
(mf-case "repeat-plusplus" "repeat.org"
	(lambda () (mf-goto "c3e6a9b2-4d7f-4a15-98ab-e4b7c0f3d6a9") (org-todo "DONE")))
(mf-case "repeat-month-end" "repeat.org"
	(lambda () (mf-goto "d4f7b0c3-5e80-4b26-a9bc-f5c8d1a4e7b0") (org-todo "DONE")))
(mf-case "repeat-plain-scheduled" "repeat.org"
	(lambda () (mf-goto "e5a8c1d4-6f91-4c37-bacd-a6d9e2b5f8c1") (org-todo "DONE")))
(mf-case "repeat-warning" "repeat.org"
	(lambda () (mf-goto "f6b9d2e5-7a02-4d48-8bde-b7eaf3c6a9d2") (org-todo "DONE")))
(mf-case "repeat-delay" "repeat.org"
	(lambda () (mf-goto "07c1e4a8-9b2f-4d63-a5e8-1c4f7b0d3e96") (org-todo "DONE")))
(mf-case "repeat-to-unknown-state" "repeat.org"
	(lambda () (mf-goto "18d2f5b9-0c3a-4e74-b6f9-2d5a8c1e4fa7") (org-todo "DONE")))
(mf-case "repeat-zero-first" "repeat.org"
	(lambda () (mf-goto "29e3a6c0-1d4b-4f85-a7b0-3e6b9d2f5ab8") (org-todo "DONE")))
(mf-case "repeat-body-stamp" "repeat.org"
	(lambda () (mf-goto "3af4b7d1-2e5c-4096-b8c1-4f7cae3a6bc9") (org-todo "DONE")))
(mf-case "repeat-no-keyword" "repeat2.org"
	(lambda () (mf-goto "28b4d6fa-1c3e-4057-9b2d-4f6c8e0a2b3d") (org-todo "DONE")))
(mf-case "repeat-habit" "repeat2.org"
	(lambda () (mf-goto "39c5e70b-2d4f-4168-8c3e-5a7d9f1b3c4e") (org-todo "DONE")))
(mf-case "repeat-to-state" "repeat2.org"
	(lambda () (mf-goto "4ad6f81c-3e50-4279-9d4f-6b8e0a2c4d5f") (org-todo "DONE")))
(mf-case "repeat-typed" "repeat2.org"
	(lambda () (mf-goto "5be70a2d-4f61-438a-8e50-7c9f1b3d5e6a") (org-todo "FINISHED")))
(let ((org-log-done 'note))
	(mf-case "repeat-closing-note" "repeat.org"
		(lambda ()
			(mf-goto "a1c4e7f0-2b5d-4893-b6e9-c2f5a8d1b4e7")
			(org-todo "DONE")
			(run-hooks 'post-command-hook)
			(mf-note "Checked."))))

;;; New entries. The user's capture templates carry :prepend t (first); the
;;; capture position last, the end of the parent's subtree, is
;;; org-insert-heading w/ respect-content, which no setting changes.

(defun mf-capture-first (target)
	"Capture \"* TODO Call the vendor\" w/ :prepend t into the current buffer's file under TARGET.
TARGET is a heading's title, or nil for the top level of the file."
	(let ((org-capture-templates
				 `(("x" "x" entry
						,(if target (list 'file+headline (buffer-file-name) target) (list 'file (buffer-file-name)))
						"* TODO Call the vendor" :prepend t :immediate-finish t))))
		(org-capture nil "x")))

(mf-case "capture-first" "outline.org" (lambda () (mf-capture-first "Projects")))
(mf-case "capture-first-top" "outline.org" (lambda () (mf-capture-first nil)))

(defun mf-append-top (title)
	"Insert a top-level heading TITLE after the last top-level subtree."
	(goto-char (point-max))
	(when (re-search-backward "^\\* " nil t) (org-back-to-heading t))
	(org-insert-heading-respect-content)
	(insert title))

(mf-case "task-append" "todo.org"
	(lambda ()
		(mf-goto "e1f6c9b3-2a85-4d07-8b4c-0d3e7a5f1b96")
		(org-insert-subheading '(4))
		(insert "Order spare contactors")
		(org-todo "TODO")
		(org-deadline nil "2026-12-01")))
(mf-case "task-append-middle" "todo.org"
	(lambda ()
		(mf-goto "c3e8b1a0-5d47-4f2a-9e6b-1a7c0d3f8e52")
		(org-insert-subheading '(4))
		(insert "Check relief valves")))
(mf-case "event-append" "calendar.org"
	(lambda ()
		(goto-char (point-max))
		(org-insert-heading-respect-content)
		(insert "Ammonia leak drill")
		(org-entry-put nil "APPT_WARNTIME" "30")
		(mf-set-timestamps (lambda () (mf-ts 2026 12 3 13 30 "-14:30") (insert "\n")))))
(mf-case "memo-append" "no-final-newline.org"
	(lambda ()
		(mf-goto "5d0a8c3f-7e21-4b94-a6d8-3c9f1e7b0a42")
		(org-insert-subheading '(4))
		(insert "Gate codes")))
(mf-case "bom-append" "bom.org"
	(lambda () (mf-append-top "Second")))
(mf-case "mixed-append" "mixed.org"
	(lambda () (mf-append-top "B")))
(mf-case "empty-append" "empty.org"
	(lambda ()
		(mf-append-top "First event")
		(mf-set-timestamps (lambda () (mf-ts 2026 12 1) (insert "\n")))))

;;; Delete and refile.

(mf-case "children-delete" "children.org"
	(lambda () (mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60") (org-cut-subtree)))
(mf-case "user-refile" "user.org"
	(lambda ()
		(let ((pos (save-excursion (mf-goto-heading "* Shop renovation") (point))))
			(mf-goto-heading "** TODO Hardware store run")
			(org-refile nil nil (list "target" (buffer-file-name) nil pos)))))
(mf-case "user-refile-top" "user.org"
	(lambda ()
		(mf-goto-heading "** WAITING Electrician quote")
		(org-refile nil nil (list "top" (buffer-file-name) nil nil))))
;; A file whose first line is a heading: outline-next-heading passes over it.
(mf-case "refile-first-top-heading" "memos.org"
	(lambda ()
		(mf-goto-heading "** Frick rep contact")
		(org-refile nil nil (list "top" (buffer-file-name) nil nil))))
;; The refile position last: the end of the target's subtree, a level deeper.
(let ((org-reverse-note-order nil))
	(mf-case "outline-refile" "outline.org"
		(lambda ()
			(let ((pos (save-excursion (mf-goto-heading "** Condenser retrofit") (point))))
				(mf-goto-heading "** TODO Oil change on C3")
				(org-refile nil nil (list "target" (buffer-file-name) nil pos))))))

;;; A refile to another file: both files change.

(defun mf-refile-across (name source heading target target-id)
	"Refile the heading of SOURCE that begins w/ HEADING under the heading of
TARGET whose :ID: is TARGET-ID, w/ `org-refile'. Write
NAME-source.expected.org and NAME-target.expected.org."
	(let ((src (mf-visit source (concat name "-source")))
				(dst (mf-visit target (concat name "-target"))))
		(unwind-protect
				(let ((pos (with-current-buffer (car dst) (mf-goto target-id) (point)))
							(file (buffer-file-name (car dst))))
					(with-current-buffer (car src)
						(mf-goto-heading heading)
						(mf-run (lambda () (org-refile nil nil (list "target" file nil pos)))))
					(mf-save src (concat name "-source"))
					(mf-save dst (concat name "-target")))
			(mf-discard src)
			(mf-discard dst))))

(mf-refile-across "refile-across" "outline.org" "*** Drain the separator"
	"memos.org" "d8a3e6f0-7b12-4c95-8e4d-5a0f2b9c1e73")

;;; Inheritance: what Org gives each heading of a few inputs.

(defun mf-inheritance-rows (input)
	"Return a line per heading of INPUT: its file, the index of its line,
`org-get-category', `org-get-tags' w/ inheritance, and `org-get-outline-path'."
	(let ((buf (find-file-noselect (expand-file-name input mf-dir)))
				rows)
		(unwind-protect
				(with-current-buffer buf
					(goto-char (point-min))
					(unless (org-at-heading-p) (outline-next-heading))
					(while (org-at-heading-p)
						(push (concat input "\t" (number-to-string (1- (line-number-at-pos)))
													"\t" (org-get-category)
													"\t" (mapconcat #'substring-no-properties (org-get-tags) ":")
													"\t" (mapconcat #'substring-no-properties (org-get-outline-path) "|"))
									rows)
						(outline-next-heading)))
			(kill-buffer buf))
		(nreverse rows)))

(with-temp-buffer
	(insert "# Written by tools/make-fixtures.el: file, heading line index, category, tags, outline path.\n")
	(dolist (input '("outline.org" "calendar.org" "todo.org" "memos.org" "user.org"))
		(dolist (row (mf-inheritance-rows input)) (insert row "\n")))
	(let ((coding-system-for-write 'utf-8-unix))
		(write-region nil nil (expand-file-name "inheritance.txt" mf-dir) nil 'silent)))

;;; Agenda: the dates of date ranges and diary sexps.

(defconst mf-agenda-texts
	'((:timestamp "<%%(diary-float t 4 2)>") (:timestamp "<%%(diary-float '(1 4 7 10) 1 -1)>")
		(:timestamp "<%%(diary-float 11 4 4)>") (:timestamp "<%%(diary-float t 1 1 15)>")
		(:timestamp "<%%(diary-anniversary 2 29 2020)>") (:timestamp "<%%(diary-anniversary 12 25)>")
		(:timestamp "<%%(diary-block 11 28 2026 12 2 2026)>") (:timestamp "<%%(diary-cyclic 10 11 20 2026)>")
		(:timestamp "<%%(diary-date 12 '(1 15) t)>") (:timestamp "<%%(diary-date t 13 2027)>")
		;; org-anniversary takes the iso order, as a timestamp & as a diary sexp line w/ its text.
		(:timestamp "<%%(org-anniversary 1970 1 15)>") (:sexp "%%(org-anniversary 2000 6 10) Ann turns %d")
		(:sexp "%%(org-anniversary 2010 08 05) Bob turns %d") (:sexp "%%(org-anniversary 2020 2 29) Leap turns %d")
		(:timestamp "<2026-11-30 Mon>--<2026-12-03 Thu>") (:timestamp "<2026-12-31 Thu 22:00>--<2027-01-01 Fri 02:00>")
		;; A range joined by one hyphen or by three, as org-tr-regexp reads it.
		(:timestamp "<2027-02-08 Mon>-<2027-02-10 Wed>") (:timestamp "<2027-03-08 Mon>---<2027-03-09 Tue>")
		;; Stamps in a property drawer and another drawer count; in a comment line, a src block, or a
		;; fixed-width line they do not; in a quote block they do.
		(:timestamp ":PROPERTIES:\n:WHEN: <2026-12-07 Mon>\n:END:") (:timestamp ":LOGBOOK:\n- Note taken on <2026-12-08 Tue>\n:END:")
		(:timestamp "# <2026-12-09 Wed> commented out") (:timestamp "#+BEGIN_SRC sh\necho <2026-12-10 Thu>\n#+END_SRC")
		(:timestamp "#+BEGIN_QUOTE\nSee <2026-12-11 Fri>.\n#+END_QUOTE") (:timestamp ": <2026-12-12 Sat> fixed width"))
	"The kind of agenda entry and the text of each row.")

(defconst mf-agenda-from (calendar-absolute-from-gregorian '(11 1 2026))
	"The first day of the agenda window: 2026-11-01.")

(defconst mf-agenda-days 900
	"The day count of the agenda window.")

(with-temp-buffer
	(insert "# Written by tools/make-fixtures.el: text (line breaks as \\n), then the agenda dates from 2026-11-01 for 900 days.\n")
	(dolist (row mf-agenda-texts)
		(let ((file (make-temp-file "mf-agenda" nil ".org" (concat "* E\n" (cadr row) "\n")))
					dates)
			(unwind-protect
					(mf-with-fixed-clock
						(dotimes (i mf-agenda-days)
							(let ((date (calendar-gregorian-from-absolute (+ mf-agenda-from i))))
								(when (org-agenda-get-day-entries file date (car row))
									(push (format "%04d-%02d-%02d" (nth 2 date) (nth 0 date) (nth 1 date)) dates)))))
				(let ((buf (find-buffer-visiting file)))
					(when buf (with-current-buffer buf (set-buffer-modified-p nil)) (kill-buffer buf)))
				(delete-file file))
			(insert (replace-regexp-in-string "\n" "\\\\n" (cadr row)) "\t" (mapconcat #'identity (nreverse dates) " ") "\n")))
	(let ((coding-system-for-write 'utf-8-unix))
		(write-region nil nil (expand-file-name "agenda.txt" mf-dir) nil 'silent)))

;;; Sexp lines: the text the agenda shows for a diary sexp line on a date it applies.

(defconst mf-sexp-lines
	'(("%%(org-anniversary 2000 6 10) Ann turns %d" (6 10 2027))
		("%%(org-anniversary 2000 6 10) Ann's %d%s birthday" (6 10 2031))
		("%%(diary-anniversary 3 12 2014) %s years; 100%%" (3 12 2027))
		("%%(diary-cyclic 10 11 20 2026) Dose %d" (11 30 2026))
		("%%(diary-float t 4 2) Meeting %d" (11 12 2026)))
	"Each sexp line, and a date (month day year) on which it applies.")

(with-temp-buffer
	(insert "# Written by tools/make-fixtures.el: sexp line, date, then the text the agenda shows.\n")
	(dolist (row mf-sexp-lines)
		(let ((file (make-temp-file "mf-sexp" nil ".org" (concat "* E\n" (car row) "\n")))
					(date (cadr row)))
			(unwind-protect
					(mf-with-fixed-clock
						(let ((items (org-agenda-get-day-entries file date :sexp)))
							(insert (car row) "\t" (format "%04d-%02d-%02d" (nth 2 date) (nth 0 date) (nth 1 date)) "\t"
											(mapconcat (lambda (i) (substring-no-properties (get-text-property 0 'txt i))) items "|") "\n")))
				(let ((buf (find-buffer-visiting file)))
					(when buf (with-current-buffer buf (set-buffer-modified-p nil)) (kill-buffer buf)))
				(delete-file file))))
	(let ((coding-system-for-write 'utf-8-unix))
		(write-region nil nil (expand-file-name "sexps.txt" mf-dir) nil 'silent)))

;;; Repeats: the dates on which the agenda shows each timestamp.

(defconst mf-repeats
	'(("plain" "<2026-01-29 Thu +1m>" -3 900) ("plain" "<2026-01-30 Fri +1m>" -3 900)
		("plain" "<2026-01-31 Sat +1m>" -3 900) ("plain" "<2027-12-31 Fri +1m>" -3 900)
		("plain" "<2028-01-29 Sat +1m>" -3 900) ("plain" "<2028-01-30 Sun +1m>" -3 900)
		("plain" "<2028-01-31 Mon +1m>" -3 900) ("plain" "<2026-03-31 Tue +1m>" -3 900)
		("plain" "<2026-08-31 Mon +2m>" -3 900) ("plain" "<2026-10-31 Sat +3m>" -3 900)
		("plain" "<2026-01-31 Sat .+1m>" -3 900) ("plain" "<2026-01-31 Sat ++1m>" -3 900)
		("plain" "<2028-02-29 Tue +1y>" -3 900) ("plain" "<2026-05-31 Sun 09:00-10:00 +1m>" -3 900)
		("plain" "<2026-11-23 Mon +2w>" -3 900) ("plain" "<2026-11-30 Mon +3d>" -3 900)
		("plain" "<2026-01-31 Sat +1m -2d>" -3 900)
		;; Windows that open long after the first date.
		("plain" "<2020-01-31 Fri +1m>" 2500 400) ("plain" "<2026-01-31 Sat +1m>" 500 400)
		("plain" "<2024-02-29 Thu +1y>" 1500 1200) ("plain" "<2026-11-30 Mon +2w>" 400 100)
		;; The warning ahead of the repeater, a zero count, and hours.
		("plain" "<2026-11-25 Wed -2d +1w>" -3 100) ("plain" "<2026-11-25 Wed +0d>" -3 100)
		("plain" "<2026-11-25 Wed 09:00 +36h>" -3 30) ("plain" "<2026-11-25 Wed 22:00 +5h>" -3 10)
		;; Planning lines, from the day after the fixed today.
		("scheduled" "<2026-12-31 Thu +1m>" 0 400) ("scheduled" "<2026-12-02 Wed .+1w>" 0 100)
		("scheduled" "<2026-12-02 Wed -2d +1w>" 0 100) ("scheduled" "<2026-12-02 Wed +1w -2d>" 0 100)
		("deadline" "<2026-12-31 Thu ++1m>" 0 400) ("deadline" "<2026-01-31 Sat +1m>" 340 400))
	"Kind, timestamp, first day as an offset from the timestamp's date, and day count of each row.")

(defun mf-repeat-dates (kind ts from days)
	"Return the dates in DAYS days from FROM (absolute) on which the agenda shows TS as KIND."
	(let* ((entry (pcase kind
									("plain" (concat "* E\n" ts "\n"))
									("scheduled" (concat "* TODO E\nSCHEDULED: " ts "\n"))
									("deadline" (concat "* TODO E\nDEADLINE: " ts "\n"))))
				 (what (pcase kind ("plain" :timestamp) ("scheduled" :scheduled) ("deadline" :deadline)))
				 (file (make-temp-file "mf-repeat" nil ".org" entry))
				 (org-deadline-warning-days 0)
				 dates)
		(unwind-protect
				(mf-with-fixed-clock
					(dotimes (i days)
						(let ((date (calendar-gregorian-from-absolute (+ from i))))
							(when (org-agenda-get-day-entries file date what)
								(push (format "%04d-%02d-%02d" (nth 2 date) (nth 0 date) (nth 1 date)) dates)))))
			(let ((buf (find-buffer-visiting file)))
				(when buf (with-current-buffer buf (set-buffer-modified-p nil)) (kill-buffer buf)))
			(delete-file file))
		(nreverse dates)))

(with-temp-buffer
	(insert "# Written by tools/make-fixtures.el: kind, timestamp, first day, last day, then the agenda dates.\n")
	(dolist (row mf-repeats)
		(pcase-let* ((`(,kind ,ts ,offset ,days) row)
								 (start (calendar-absolute-from-gregorian (org-date-to-gregorian ts)))
								 (from (if (equal kind "plain") (+ start offset)
												 (max (+ start offset) (1+ (time-to-days mf-now)))))
								 (fmt (lambda (abs)
												(let ((d (calendar-gregorian-from-absolute abs)))
													(format "%04d-%02d-%02d" (nth 2 d) (nth 0 d) (nth 1 d))))))
			(insert kind "\t" ts "\t" (funcall fmt from) "\t" (funcall fmt (+ from days -1)) "\t"
							(mapconcat #'identity (mf-repeat-dates kind ts from days) " ") "\n")))
	(let ((coding-system-for-write 'utf-8-unix))
		(write-region nil nil (expand-file-name "repeats.txt" mf-dir) nil 'silent)))

;;; Due: a missed SCHEDULED or DEADLINE item shows on today until it is done, and a deadline
;;; warns on today ahead of its date.

(defconst mf-due
	'("* TODO E\nSCHEDULED: <2026-11-17 Tue>" "* DONE E\nSCHEDULED: <2026-11-17 Tue>"
		"* E\nSCHEDULED: <2026-11-17 Tue>" "* TODO E\nSCHEDULED: [2026-11-17 Tue]"
		"* TODO E\nDEADLINE: <2026-11-10 Tue>" "* BELAYED E\nDEADLINE: <2026-11-10 Tue>"
		"* TODO E\nSCHEDULED: <2026-11-17 Tue 09:00 +1w>" "* TODO E\nDEADLINE: <2026-11-20 Fri +1m>"
		"* TODO E\nSCHEDULED: <2026-11-17 Tue> DEADLINE: <2026-11-19 Thu>"
		;; A delay hides the item until it has run.
		"* TODO E\nSCHEDULED: <2026-11-20 Fri -5d>" "* TODO E\nSCHEDULED: <2026-11-20 Fri -2d>"
		"* TODO E\nSCHEDULED: <2026-11-10 Tue -1w>" "* TODO E\nSCHEDULED: <2026-11-20 Fri -1w>"
		;; A deadline warns on today from org-deadline-warning-days ahead, or from its own cookie.
		"* TODO E\nDEADLINE: <2026-11-26 Thu>" "* DONE E\nDEADLINE: <2026-11-26 Thu>"
		"* TODO E\nDEADLINE: <2026-12-07 Mon>" "* TODO E\nDEADLINE: <2026-12-08 Tue>"
		"* TODO E\nDEADLINE: <2026-11-30 Mon +1w>" "* TODO E\nDEADLINE: <2026-11-25 Wed -3d>"
		"* TODO E\nDEADLINE: <2026-12-03 Thu -3d>")
	"The text of each entry.")

(with-temp-buffer
	(insert "# Written by tools/make-fixtures.el: entry (line breaks as \\n), then its SCHEDULED and DEADLINE dates from today, 2026-11-23, for 7 days.\n")
	(dolist (text mf-due)
		(let ((file (make-temp-file "mf-due" nil ".org" (concat text "\n")))
					dates)
			(unwind-protect
					(mf-with-fixed-clock
						(dotimes (i 7)
							(let ((date (calendar-gregorian-from-absolute (+ (time-to-days mf-now) i))))
								(when (org-agenda-get-day-entries file date :deadline :scheduled)
									(push (format "%04d-%02d-%02d" (nth 2 date) (nth 0 date) (nth 1 date)) dates)))))
				(let ((buf (find-buffer-visiting file)))
					(when buf (with-current-buffer buf (set-buffer-modified-p nil)) (kill-buffer buf)))
				(delete-file file))
			(insert (replace-regexp-in-string "\n" "\\\\n" text) "\t" (mapconcat #'identity (nreverse dates) " ") "\n")))
	(let ((coding-system-for-write 'utf-8-unix))
		(write-region nil nil (expand-file-name "due.txt" mf-dir) nil 'silent)))

;;; make-fixtures.el ends here
