---
name: review-phase-core
description: Provide one concise, risk-prioritized reading guide for the core code of an AI-completed project Phase X.X. Use when the user invokes $review-phase-core or asks which code is essential to review after a phase, wants exact clickable code-line links and brief logic explanations, or wants macro-level understanding without reading the full implementation.
---

# Review Phase Core

Give the user one self-contained answer that identifies only the essential code
needed to understand and review a completed phase.

## Inspect silently

1. Read repository instructions.
2. Identify the requested Phase X.X from the prompt and recent context. If it is
   omitted, infer it from the latest phase plan and current changes instead of
   asking unless there are multiple equally plausible phases.
3. Read the relevant plan/spec acceptance section and inspect the actual
   implementation. Use `git status --short` as well as diffs so untracked files
   are not missed.
4. Trace the phase's main control/data flow and find where it:
   - reads authoritative state;
   - filters or transforms information;
   - mutates game state or performs actions;
   - defines public state or allowed inputs;
   - crosses protocol, process, thread, permission, or persistence boundaries;
   - handles failure and lifecycle transitions.
5. Select 4–8 production-code locations that explain those decisions. Prefer
   methods, constructors, and state transitions over whole files.
6. Select 2–4 tests that prove the highest-risk invariants.

Remain read-only. Do not edit code, run tests, produce a defect audit, or propose
new process/documentation unless the user separately asks. Do not expose the
inspection process in the final response.

## Prioritize

Use only the categories that apply:

1. **第一优先级：必须看**
   Authority, hidden information, state mutation, security, concurrency,
   protocol, persistence, or lifecycle boundaries.
2. **第二优先级：理解结构**
   Public APIs, state models, orchestration, and important transformations.
3. **最后看测试**
   Tests that directly prove the preceding invariants.

Clearly state in one sentence what this phase implements and what similarly
named concerns belong to later phases.

## Answer format

For every selected location:

- Link directly to the exact local file and starting line.
- Name the method/class and the question it answers.
- Give 1–3 plain-language bullets explaining the logic or invariant to verify.

End with:

- **可以跳过**: boilerplate, repeated codecs, getters, fixtures, or mechanical
  helpers that do not control behavior.
- **最短阅读顺序**: one short `entry → model → boundary → validation → tests`
  chain.

Keep the response compact. Do not dump code, enumerate every changed file, give
a general code-review lecture, or repeat test output. Mention a defect only when
it changes which code the user must understand.



