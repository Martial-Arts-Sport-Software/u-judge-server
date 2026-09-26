---
name: u-judge-increment-planning
description: Use when choosing the next U'Judge task, scoping or splitting work, creating an issue or branch, or preparing and describing a PR.
---

# U'Judge Increment Planning

The unit of planning and review is a roadmap increment, not a single command, transition, table or publisher.
Follow `AGENTS.md`; this skill turns it into steps.

## 1. Choose the increment

1. Open "Инкременты поставки" in `docs/ROADMAP.md` and take the first increment with status `[ ]` whose dependencies are met.
2. Write its scenario as one demo script: what the operator or judge does in the running application and what they see.
3. List the requirement IDs and gate items the increment will close. If none can be closed, the scope is too small.
4. Check `server/src/main/kotlin/org/mass/Server.kt` (`Server.start()`) and the desktop entry point: the scenario must run
   through them, not only through tests.

## 2. Size check

- Target 3-7 working days and one issue. Up to 2-3 sequential PRs under one issue are allowed when each leaves `main`
  working; only the last one marks statuses.
- Too big: split by scenario (for example "Tanbon" and "technical disciplines"), never by layer.
- Too small: a PR adding one command/transition/table/DTO/publisher belongs as a commit inside the increment.
- Narrow PR exceptions: urgent fix, blocking preparation, isolated CI/docs change, prerequisite that cannot be verified
  inside the increment. State the parent increment and the next work.

## 3. Issue, branch, commits

- Create the issue first: title `<Type> | <Imperative Description>`, parent increment, requirement IDs, scope, demo
  script as acceptance criteria, known limitations. Set assignee `TheGeniusOfEternity`, the gate milestone
  (`v1 Pilot | G<n> ...`), one `type: *` label, relevant `area: *` and `priority: *` labels, and `pilot`. Add it to the
  GitHub Project, or ask the user which Project to use.
- Branch from local `main` as `<type>/<short-kebab-scope>`; do not fetch or pull without an explicit request.
- Commit in small Conventional Commits (`<type>(<scope>): <imperative description>`), each building on its own.

## 4. Definition of done

- The demo script runs from `./gradlew :desktop:run` (or the installer) with production wiring.
- A scenario-level acceptance test covers the happy path, retry/duplicate, restart and rejection.
- `./gradlew build` and `git diff --check` pass; persistence changes also pass `RealPostgresLifecycleTest`.
- The increment row in `docs/ROADMAP.md` and the stage evidence table are updated once; README/PROJECT keep only the
  short current state. Mark `[x]` only for closed requirements or gate items with evidence.

## 5. Pull request

- Title `<Type> | <Imperative Description>`; same metadata as the issue.
- Body sections: Goal (increment and scenario), Changes, Evidence (commands and results, demo notes, device/OS),
  Requirements closed, Known limitations, `Closes #<issue>`.
