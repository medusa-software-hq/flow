# M4 follow-ups

Deliberately out of scope for M4 (the Claude Agent engine), tracked here so they
aren't lost.

- **Per-user auth / quotas.** M4's auth ladder is single-operator: the personal
  subscription rung is valid only while one operator runs their own sessions
  (the tripwire). Multi-user Flow — per-user API keys or a quota/billing model —
  is a later milestone. Rung 3 (Vertex, project-level billing) may make per-user
  keys unnecessary; revisit when Flow has users.
- **Web tools policy.** The engine's tool policy disallows git-push/GitHub tools
  (publishing belongs to the publisher) and leaves web/fetch tools **off** by
  default. Revisit enabling web tools when there's a concrete need (e.g. tasks
  that legitimately need to read external docs), with the sandboxing implications
  considered.
- **Anthropic Managed Agents (watch item).** Hosted, Anthropic-run sandboxes are
  a different execution model from Flow's "work on our checkout, we publish."
  Interesting if a hosted-sandbox execution tier ever becomes attractive; not a
  fit for M4. No action — just watch.

Not a follow-up any more: the **cloud-hosted worker** is no longer a "someday"
item — it's **Path B** of this milestone (`ms-workload`), which is where an
always-on hosted worker (and the deployed-stack claude demo) lands.
