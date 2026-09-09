# Guidance for AI coding agents and review bots

This file applies to any AI agent working in this repository — coding assistants making
changes, and automated PR review bots evaluating them.

## Scope discipline

- Implement only what was asked. Do not add features, endpoints, config options, or
  abstractions "while you're in there" unless explicitly requested.
- A bug fix should fix the bug, not refactor the surrounding code, rename unrelated things, or
  add defensive handling for scenarios that can't occur.
- Prefer three similar lines over a shared abstraction introduced for a single current use. Don't
  design for hypothetical future requirements.
- No half-finished implementations and no speculative feature flags "for later."

## Error handling

- Don't add validation, null checks, or try/catch for states that can't actually happen given
  this codebase's own guarantees. Validate only at real boundaries (user input, external APIs).
- Don't silently swallow errors. If a failure path is genuinely impossible, that's not a reason to
  add a catch block "just in case" — it's a reason to leave it alone.

## Comments and diffs

- Default to no comments. A comment is only warranted for a non-obvious "why" (a hidden
  constraint, a workaround for a specific bug, a subtle invariant) — never to restate what the
  code already does.
- Keep diffs proportional to the task. A one-line fix does not need a surrounding cleanup pass.

## For review bots specifically

- Flag correctness bugs, security issues, and missing test coverage as blocking.
- Treat style preferences, alternative architectures, and "this could be more general" suggestions
  as optional discussion points, not requirements — do not request changes solely on that basis.
- Before suggesting a new abstraction, framework, or dependency, check whether the codebase
  already has an equivalent pattern nearby and prefer matching it over introducing something new.
- If a suggestion would meaningfully grow the diff or add new surface area beyond the PR's stated
  purpose, say so explicitly and let the author decide — don't treat it as a requirement to merge.
