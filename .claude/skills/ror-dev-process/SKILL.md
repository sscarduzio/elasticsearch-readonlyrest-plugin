---
name: ror-dev-process
description: ROR team development process and review culture — PR preparation conventions, changelog format, review etiquette, task lifecycle. Use when preparing/reviewing PRs or managing Jira tasks for ReadonlyREST.
---

# ROR Development Process & Review Culture

Source of truth: `beshu-tech/readonlyrest-internal/development_guide.md`. This skill distills the parts that govern PRs and reviews.

## Preparing a PR (binding conventions)

- Branch name carries the Jira number: `bugfix/RORDEV-302`, `feature/RORDEV-xxx`
- PR title: `[RORDEV-NNN] short description`
- PR description starts with a **changelog phrase in the release convention** — emoji style, client perspective, copy-paste ready (leave blank for purely internal changes):
  - `🚨**Security Fix** (ES) search template handling fix`
  - `🚀**New** (ES) 7.9.0 support`
  - `🧐**Enhancement** (ES) full support for ES Snapshots and Restore APIs`
  - `🐞**Fix** (KBN) fix crash in error handling`
- Pipelines must pass. Set the next pre-version before opening the PR.
- New ES version support: PR must come from the main repo (not a fork — `ES_S3_UP` needs S3 credentials); add the version to `bin/upload_es_artifacts.sh`.

## Review culture (the core rule)

**No comment goes unanswered** before the next review round or merge. A thumbs-up suffices; an elaborate disagreement is fine — anything that proves the submitter saw it. Once answered, **the comment's author resolves the thread** if happy with the outcome. Click GitHub's `request review` for each review iteration.

## Code placement & testing

- ES-independent shared code → `core` module
- ROR core change → unit test; ES-coupled change → integration test
- Test suites are organized **per ES API, not per ROR feature** (`IndicesAPISuite` ✅, `AuthRuleSuite` ❌)

## Task lifecycle (Jira: RORDEV board)

Analysis (always first: reproduce BUGs — flag in Jira if >8h; design-check FEATUREs) → estimate in hours in `Story point estimate` (skip if <16h; INVESTIGATIONs report progress every 24h instead) → implementation → testing → PR → review → customer notification → close (PR link in Jira, move to DONE). One task in `IN PROGRESS` at a time; blocked → `ON HOLD` **with a comment written for someone not involved in the ticket**. Tag every task `R&D` or `Support` (Support = reactive to a user/customer initiative). Track time in Clockify: `[Action] [What] – [Why/Context]`.

## Kibana feature-enablement principles

1. Never accidentally enable paid features. 2. Users must not lose free features by installing ROR. 3. Customers with both ROR + Elastic licenses keep what they paid for. 4. ROR-incompatible features get disabled or hidden.

## Customer notification

After merge to develop, CI uploads binaries to S3 — the developer notifies the reporting user with the build link and asks for a test. Jira tasks begin with `REPORT TO: ...`; if absent, contact the issue reporter.
