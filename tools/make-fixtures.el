;;; make-fixtures.el --- Make the Emacs-made fixtures of org-core  -*- lexical-binding: t; -*-

;; Run from the repository root:
;;
;;	emacs --batch -l tools/make-fixtures.el
;;
;; For each field-edit case it reads an input fixture from
;; org-core/src/test/resources/, applies the edit through Org's own
;; commands, and writes <case>.expected.org beside it. It also writes
;; repeats.txt, the dates on which Org's agenda shows each repeating
;; timestamp of a list. The Kotlin tests compare org-core against both.
;;
;; The clock, the UUIDs, the locale, and the Org settings are fixed, and
;; each file keeps the line endings it was read with, so a second run
;; gives no diff.

(require 'cl-lib)
(require 'org)
(require 'org-id)
(require 'org-agenda)

(defconst mf-dir (expand-file-name "org-core/src/test/resources/" default-directory)
	"The fixture folder.")

(defconst mf-now (encode-time (list 0 30 14 23 11 2026 nil -1 nil))
	"The fixed clock: 2026-11-23 14:30 local time.")

(defconst mf-new-id "11111111-2222-4333-8444-555555555555"
	"The UUID that `org-id-get-create' gets for a new ID.")

;; Org's defaults where the design names them, and English day names.
(setq org-tags-column -77
	org-auto-align-tags t
	org-log-done 'time
	org-log-into-drawer nil
	org-log-repeat 'time
	org-todo-log-states nil
	org-adapt-indentation nil
	org-closed-keep-when-no-todo nil
	org-id-track-globally nil
	org-id-method 'uuid
	org-agenda-skip-timestamp-if-done nil
	org-agenda-show-future-repeats t
	org-agenda-prefer-last-repeat nil
	system-time-locale "C"
	make-backup-files nil)

(defun mf-goto (id)
	"Move point to the heading whose :ID: is ID."
	(goto-char (point-min))
	(unless (re-search-forward (concat "^:ID:[ \t]+" (regexp-quote id) "[ \t]*$") nil t)
		(error "No heading with ID %s" id))
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

(defun mf-case (name input fn)
	"Visit a copy of INPUT, call FN at the start of the buffer, and write NAME.expected.org.
Emacs detects the coding system and line endings as it does for any file
(a file of mixed line endings reads as LF w/ the carriage returns kept as
text), and the output keeps them. A state-change log note, which Org
writes from `post-command-hook', is written ere the file is saved."
	(let* ((in (expand-file-name input mf-dir))
				 (out (expand-file-name (concat name ".expected.org") mf-dir))
				 (tmp (expand-file-name (concat "mf-" name ".org") temporary-file-directory)))
		(copy-file in tmp t)
		(let ((buf (find-file-noselect tmp)))
			(unwind-protect
					(with-current-buffer buf
						(let ((coding buffer-file-coding-system))
							;; An ASCII file reads as undecided; write it as UTF-8 w/ the same line endings.
							(when (eq (coding-system-base coding) 'undecided)
								(setq coding (coding-system-change-text-conversion coding 'utf-8)))
							;; A file w/o a line ending (an empty one) gets LF on every system.
							(when (or (= (buffer-size) 0) (vectorp (coding-system-eol-type coding)))
								(setq coding (coding-system-change-eol-conversion coding 'unix)))
							(goto-char (point-min))
							(mf-with-fixed-clock
								(cl-letf (((symbol-function 'org-id-new) (lambda (&rest _) mf-new-id)))
									(funcall fn)
									(run-hooks 'post-command-hook)))
							(let ((coding-system-for-write coding))
								(write-region nil nil out nil 'silent))))
				(with-current-buffer buf (set-buffer-modified-p nil))
				(kill-buffer buf)
				(delete-file tmp)))))

(defun mf-id ()
	"Give the current heading an :ID: w/ `org-id-get-create'."
	(org-id-get-create))

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

;;; Field edits of todo.org.

(mf-case "todo-done" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-todo "DONE")))
(mf-case "todo-reopen" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-todo "TODO")))
(mf-case "todo-next" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-todo "NEXT")))
(mf-case "deadline-set" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-deadline nil "2026-11-27")))
(mf-case "deadline-remove" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-deadline '(4))))
(mf-case "schedule-add" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-schedule nil "2026-11-30")))
(mf-case "schedule-remove" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-schedule '(4))))
(mf-case "priority-set" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-priority ?A)))
(mf-case "priority-remove" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-priority 'remove)))
(mf-case "tags-set" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-set-tags '("work" "plc"))))
(mf-case "tags-clear" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-set-tags nil)))
(mf-case "title-edit" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (org-edit-headline "Order VFD spares & fuses")))
(mf-case "body-edit" "todo.org"
	(lambda () (mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37") (mf-set-body "Two ACS580s, frame R3.\nCheck stock first.")))
(mf-case "category-rename" "todo.org"
	(lambda ()
		(mf-goto "c3e8b1a0-5d47-4f2a-9e6b-1a7c0d3f8e52")
		(org-edit-headline "Shop")
		(org-entry-put nil "CATEGORY" "Shop")))
(mf-case "task-append" "todo.org"
	(lambda ()
		(mf-goto "e1f6c9b3-2a85-4d07-8b4c-0d3e7a5f1b96")
		(org-insert-subheading '(4))
		(insert "Order spare contactors")
		(org-todo "TODO")
		(mf-id)
		(org-deadline nil "2026-12-01")))
(mf-case "task-append-middle" "todo.org"
	(lambda ()
		(mf-goto "c3e8b1a0-5d47-4f2a-9e6b-1a7c0d3f8e52")
		(org-insert-subheading '(4))
		(insert "Check relief valves")
		(mf-id)))
(mf-case "task-delete" "todo.org"
	(lambda () (mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04") (org-cut-subtree)))

;;; Field edits of calendar.org.

(mf-case "warn-set" "calendar.org"
	(lambda () (mf-goto "0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88") (org-entry-put nil "APPT_WARNTIME" "30")))
(mf-case "warn-change" "calendar.org"
	(lambda () (mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10") (org-entry-put nil "APPT_WARNTIME" "45")))
(mf-case "warn-remove" "calendar.org"
	(lambda () (mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10") (org-entry-delete nil "APPT_WARNTIME")))
(mf-case "timestamp-change" "calendar.org"
	(lambda ()
		(mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10")
		(mf-set-timestamps (lambda () (mf-ts 2026 11 24 10 0 "-11:30") (insert "\n")))))
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
(mf-case "event-tags" "calendar.org"
	(lambda () (mf-goto "6f1d2c4e-8a1b-4c55-9e0a-2b7f1d9c3a10") (org-set-tags '("private"))))
(mf-case "event-title" "calendar.org"
	(lambda () (mf-goto "0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88") (org-edit-headline "Office closed for Thanksgiving")))
(mf-case "event-append" "calendar.org"
	(lambda ()
		(goto-char (point-max))
		(org-insert-heading-respect-content)
		(insert "Ammonia leak drill")
		(mf-id)
		(org-entry-put nil "APPT_WARNTIME" "30")
		(mf-set-timestamps (lambda () (mf-ts 2026 12 3 13 30 "-14:30") (insert "\n")))))
(mf-case "event-delete" "calendar.org"
	(lambda () (mf-goto "0b7e3a52-41d9-4f0e-a3c6-7d1e9b2f4c88") (org-cut-subtree)))

;;; Edge files.

(mf-case "crlf-done" "crlf.org"
	(lambda () (mf-goto "b85d1e3a-6c07-4f29-9a4e-0d7f2c8b1e53") (org-todo "DONE")))
(mf-case "logbook-done" "logbook.org"
	(lambda () (mf-goto "6a2f9e0b-4d71-4c53-b8e2-1f0c7a9d3e58") (org-todo "DONE")))
(mf-case "logbook-body" "logbook.org"
	(lambda () (mf-goto "6a2f9e0b-4d71-4c53-b8e2-1f0c7a9d3e58") (mf-set-body "Use the Fluke 754.\nZero at 0 psig.")))
(mf-case "foreign-warn" "foreign.org"
	(lambda () (mf-goto "e4b7a1c9-0d52-4e8f-b3a6-7c1d9f2e0a85") (org-entry-put nil "APPT_WARNTIME" "20")))
(mf-case "missing-ids" "noid.org"
	(lambda ()
		(goto-char (point-min))
		(re-search-forward "^\\* Shop")
		(mf-id)
		(re-search-forward "^\\*\\* TODO Check slide valve")
		(mf-id)))
(mf-case "write-adds-ids" "noid.org"
	(lambda ()
		(mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60")
		(org-edit-headline "Rebuild compressor 4")
		(goto-char (point-min))
		(re-search-forward "^\\* Shop")
		(mf-id)
		(re-search-forward "^\\*\\* TODO Check slide valve")
		(mf-id)))
(mf-case "children-delete" "children.org"
	(lambda () (mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60") (org-cut-subtree)))
(mf-case "children-title" "children.org"
	(lambda () (mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60") (org-edit-headline "Rebuild compressor 3, RWF II")))
(mf-case "memo-title" "memos.org"
	(lambda () (mf-goto "a2d5f8c1-6e39-4b07-9c4a-3f1b7e0d2c65") (org-edit-headline "Frick rep, RWF II")))
(mf-case "memo-append" "no-final-newline.org"
	(lambda ()
		(mf-goto "5d0a8c3f-7e21-4b94-a6d8-3c9f1e7b0a42")
		(org-insert-subheading '(4))
		(insert "Gate codes")
		(mf-id)))

;;; Escapes: Org must read no tags and no keyword from an escaped title.

(mf-case "escape-title" "todo.org"
	(lambda ()
		(mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04")
		(org-edit-headline (concat "Check the list :hot:" (string #x200b)))
		(unless (null (org-get-tags nil t)) (error "Escaped title reads as tags"))))
(mf-case "escape-keyword" "todo.org"
	(lambda ()
		(mf-goto "7d0b4f2e-9c68-4a1d-b3e5-2f9a1c8d6e04")
		(org-priority 'remove)
		(org-todo 'none)
		(org-edit-headline (concat (string #x200b) "TODO list review"))
		(unless (null (org-get-todo-state)) (error "Escaped title reads as a keyword"))))

;;; Rebase: Emacs marks a task DONE while the app edits its title.

(mf-case "rebase-title" "todo.org"
	(lambda ()
		(mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37")
		(org-todo "DONE")
		(org-edit-headline "Order VFD spares & fuses")))

;;; Repeating tasks marked done: org-auto-repeat-maybe shifts the dates.

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

;;; A planning keyword added beside the other one.

(mf-case "schedule-beside-deadline" "children.org"
	(lambda () (mf-goto "0f9c2a7e-3d58-4b16-a7c9-5e1b8d4f2a60") (org-schedule nil "2026-12-02")))
(mf-case "deadline-beside-schedule" "logbook.org"
	(lambda () (mf-goto "6a2f9e0b-4d71-4c53-b8e2-1f0c7a9d3e58") (org-deadline nil "2026-11-28")))

;;; The last property of a drawer deleted: org-entry-delete drops the drawer.

(mf-case "drawer-remove" "children.org"
	(lambda () (mf-goto "5b8c2e7f-9a14-4d63-b0e7-6f3a1c9d2e85") (org-entry-delete nil "ID")))

;;; A BOM, mixed line endings, and an empty file.

(defun mf-append-top (title)
	"Insert a top-level heading TITLE after the last top-level subtree, w/ an :ID:."
	(goto-char (point-max))
	(when (re-search-backward "^\\* " nil t) (org-back-to-heading t))
	(org-insert-heading-respect-content)
	(insert title)
	(mf-id))

(mf-case "bom-title" "bom.org"
	(lambda () (mf-goto "9e2c4f7a-6b03-4d18-a5e9-1c8f0b3d7a64") (org-edit-headline "Notes")))
(mf-case "bom-append" "bom.org"
	(lambda () (mf-append-top "Second")))
(mf-case "mixed-warn" "mixed.org"
	(lambda () (mf-goto "4a1f7c0e-2b96-4d38-8e5a-7c3d0f9b6e21") (org-entry-put nil "APPT_WARNTIME" "10")))
(mf-case "mixed-append" "mixed.org"
	(lambda () (mf-append-top "B")))
(mf-case "empty-append" "empty.org"
	(lambda ()
		(mf-append-top "First event")
		(mf-set-timestamps (lambda () (mf-ts 2026 12 1) (insert "\n")))))

;;; Categories: a task refiled to another, and a missing one made on first filing.

(mf-case "task-move" "todo.org"
	(lambda ()
		(let ((pos (save-excursion (mf-goto "e1f6c9b3-2a85-4d07-8b4c-0d3e7a5f1b96") (point))))
			(mf-goto "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37")
			(org-refile nil nil (list "Unfiled" (buffer-file-name) nil pos)))))
(defun mf-refile (id target)
	"Refile the heading ID under the heading TARGET (an ID) w/ `org-refile'."
	(let ((pos (save-excursion (mf-goto target) (point))))
		(mf-goto id)
		(org-refile nil nil (list "target" (buffer-file-name) nil pos))))

(mf-case "note-move-middle" "move.org"
	(lambda () (mf-refile "a4f61cd8-3059-4eb7-a2ca-5d91bf7e306c" "71c3e9a5-0d26-4b84-9f17-2a6e8c4b0d39")))
(mf-case "note-move-end" "move.org"
	(lambda () (mf-refile "82d4fab6-1e37-4c95-80a8-3b7f9d5c1e4a" "93e50bc7-2f48-4da6-91b9-4c80ae6d2f5b")))
(mf-case "move-new-category" "todo.org"
	(lambda ()
		(mf-append-top "Shop")
		(org-entry-put nil "CATEGORY" "Shop")
		(mf-refile "5e2a9d7c-0f13-4b86-a4d2-8c1e6b0f9a37" mf-new-id)))
(mf-case "category-create" "children.org"
	(lambda ()
		(mf-append-top "Unfiled")
		(org-entry-put nil "CATEGORY" "Unfiled")
		(org-insert-subheading '(4))
		(insert "Order gaskets")
		(org-todo "TODO")
		(mf-id)))

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

;;; make-fixtures.el ends here
