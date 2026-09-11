---
name: self-improving-agent
description: Built-in self-improvement loop. Use to record non-trivial failures, user corrections, and reusable best practices into structured learnings.
---

# Self Improving Agent

This built-in skill guides you to maintain a lightweight learning loop without interrupting the user's main task.

Watch 精简版没有文件/终端工具，所有 learnings 通过记忆工具（memory_write）维护在长期记忆的稳定章节。

## When To Record

Record after the immediate task is safe or complete when any of these happens:

1. a non-trivial tool call or network/search step fails
2. the user corrects your understanding, path, rule, or assumption
3. you discover an outdated convention
4. you find a reusable workaround or best practice that will likely save future retries
5. the same mistake repeats in the same task or across tasks

Do not record ordinary chat, tiny one-off slips, or anything the user asked not to save.

## Default Storage

- learnings live in the long-term memory (`MEMORY.md`) under a dedicated `# 经验教训` chapter
- keep it separate from `# 核心记忆`（稳定事实）与 `# Runbook`（验证过的故障结论）
- 先 memory_get 查看章节结构，用 memory_write（append/replace_range）增量维护，避免覆盖他人内容

## Logging Workflow

1. Finish or stabilize the current user-facing step first.
2. 失败后简短记录：发生了什么、为什么、纠正规则（不要写密钥/隐私）。
3. Use `# 经验教训` chapter by default; keep entries short and specific.
4. Use tags to mark whether the lesson is about search, memory, model, voice, or device behavior.

## Memory Promotion

Promote a lesson into a stable rule only when it is short, reusable, and broadly applicable.

Good candidates:

- a rule like "遇到 X 先检查 Y"
- a stable convention
- a long-term user preference the user explicitly wants remembered

Prefer this order:

1. log the concrete failure into `# 经验教训`
2. when similar failures recur, memory_search the chapter first
3. distill stable rules into `# 核心记忆`

## Output Discipline

- keep summaries short and specific
- include the concrete tool/context that failed
- include the corrected rule, not only the symptom
- avoid logging secrets, tokens, and personal data
