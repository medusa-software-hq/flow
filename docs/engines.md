# Engines

Flow can run a session with one of two **engines** — the agent that actually
does the work. You pick per session; the rest of Flow (issues, PRs, the web app,
auto mode) is identical either way.

| Engine | What it is | Best for |
|---|---|---|
| **Builtin** | Flow's own agentic loop (the frontline/expert/interpreter system in the [README](../README.md)). Needs a `project.yaml` manifest to know how to build and test the repo. | Repositories set up for Flow, where you want Flow's own loop. |
| **Claude Agent** | Drives **Claude Code** as a subprocess — Anthropic's shipped tools, agent loop, and context management. Works on **any repository**, `project.yaml` or not. | Arbitrary repos, especially ones without a Flow manifest. |

> "Claude Agent" / "Powered by Claude". Same publishing, PR conventions, and
> review flow as Builtin — only the agent in the middle differs. Flow's
> publisher still owns the branch and PR; the engine never pushes or opens PRs
> itself.

## Choosing the engine

- **Web app** — the new-session form has an **Engine** dropdown: *Builtin* or
  *Claude Agent*. Leave it unset to use the worker's default.
- **Auto mode** — label the issue **`flow:engine=claude`** (alongside
  [`flow:ready`](auto-mode.md)) to run it on the Claude Agent engine. No label →
  the worker's default engine.
- **Which worker picks it up** — a worker only claims sessions for engines it can
  run (its `FLOW_WORKER_ENGINES`), and never blocks behind a session it can't; a
  session with no engine chosen runs on the worker's default. So a Claude-Agent
  session simply waits for a Claude-capable worker.

## Manifest-optional (the Claude Agent headline)

The Claude Agent engine runs one of two ways depending on the repo:

- **With a `project.yaml`** (*gated*): after Claude works, Flow runs the repo's
  analyze/test gate. If it's red, Flow **bounces** the diagnostics back into the
  same Claude session ("these checks fail: … fix and stop when green") a few
  times; if it recovers, you get a PR, otherwise the session fails honestly with
  the final diagnostics. A broken baseline (checks already failing before Claude
  touched anything) fails fast without spending a run.
- **Without a `project.yaml`** (*manifest-less*): Flow skips its own gate and
  instructs Claude to discover and run the repository's *own* checks; the PR's
  own CI is the arbiter. This is what lets Flow work repos it was never set up
  for.

The session's event feed says which mode ran.

## Cost

Every Claude Agent session shows its **dollar cost** — on the session detail's
final state line and as a **Cost** column in the sessions list. Builtin sessions
don't show a cost line. A session that hits its per-session budget cap fails
cleanly, naming the cap.

## For operators — auth

The Claude Agent engine authenticates to Anthropic via an **auth ladder** (see
[`plan/m4/design/02-auth-and-modes.md`](../plan/m4/design/02-auth-and-modes.md)
and [`worker/README.md`](../worker/README.md)):

- **Personal subscription** (the default everywhere) — `CLAUDE_CODE_OAUTH_TOKEN`
  from `claude setup-token`, or your logged-in CLI on your own machine. Running
  your own sessions on your own subscription is personal automation, wherever the
  worker runs.
- **API key** (`ANTHROPIC_API_KEY`) / **Vertex** — the multi-user / scale-out
  options.

**The tripwire:** the personal-subscription rung is only valid while Flow runs a
**single operator's** own sessions. The moment a *second person's* sessions would
run on it, that's offering claude.ai limits to users — switch to the API-key or
Vertex rung. (Location — laptop, CI, a hosted worker — is *not* the tripwire;
whose sessions on whose subscription is.)

Select the rung with `FLOW_CLAUDE_AUTH=personal|api-key|vertex` (default
`personal`) on the worker.
