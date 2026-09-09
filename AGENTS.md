# Guidance for AI coding agents and review bots

Applies to any AI agent working in this repository: coding assistants making changes, and
automated PR review bots evaluating them.

This is a small, lightly-loaded tool, not a target for state-of-the-art engineering. Prefer the
shorter, more direct implementation over the more "correct-looking" one: short comments,
one-liners where reasonable, minimal ceremony. Development efficiency matters more than exhaustive
polish here - both when writing code and when reviewing it.

## For agents making changes

- Implement only what was asked. Do not add features, endpoints, config options, or
  abstractions "while you're in there" unless explicitly requested.
- A bug fix should fix the bug, not refactor the surrounding code, rename unrelated things, or
  add defensive handling for scenarios that can't occur.
- Prefer three similar lines over a shared abstraction introduced for a single current use. Don't
  design for hypothetical future requirements.
- No half-finished implementations and no speculative feature flags "for later."
- Don't add validation, null checks, or try/catch for states that can't actually happen given
  this codebase's own guarantees. Validate only at real boundaries (user input, external APIs).
- Don't silently swallow errors. A failure path being unreachable is a reason to leave it alone,
  not a reason to add a catch block "just in case."
- Default to no comments. Add one only for a non-obvious "why" (a hidden constraint, a workaround
  for a specific bug, a subtle invariant) — never to restate what the code already does.
- Keep diffs proportional to the task. A one-line fix does not need a surrounding cleanup pass.

## For review bots

`tb-monitoring` is a small standalone single-JVM process (one single-threaded scheduler, a handful
of targets, one cycle per `monitoring_rate_ms`) — calibrate severity to that, not to a
multi-tenant/high-scale standard.

Read a change against its actual purpose (what does this probe need to be true to be
trustworthy?) rather than running a fixed checklist over every line.

Report as blocking:
- Wrong or missing metric/alert data, or broken probe/check behavior — for a monitoring tool,
  correct data is the product, not a detail.
- Startup or shutdown failures, and security issues.
- Missing test coverage for actual probe/alerting logic.

Don't report as blocking:
- Naming, DRY, method-family ergonomics, or "this could be more general/reusable."
- An unevicted map/set entry for a retired target — not a leak in a process that restarts every
  release.
- Re-parsing a URI or re-resolving a label once per cycle — microseconds per minute, not a
  performance problem.
- Edge-branch coverage in pure utility functions.
- A fix that adds new state/indirection (a cache, an enum, a static initializer) to guard a
  configuration that doesn't actually occur in this deployment — that's pure cost, not risk
  reduction.

Before suggesting a new abstraction, framework, or dependency, check whether the codebase already
has an equivalent pattern nearby and prefer matching it.

A defect (wrong output, broken behavior, security issue) is always worth raising. A preference
(alternative structure, naming) is worth raising once — if the author declines it, explicitly or
by not acting on it, drop it; don't re-raise the same declined point in a later review round. Keep
comments terse and specific — point at the concrete risk, don't restate context already visible
in the diff.

If a suggestion would meaningfully grow the diff or add surface area beyond the PR's stated
purpose, say so and let the author decide — don't treat it as required to merge.
