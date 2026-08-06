# Engines

Flow can run a session with one of three **engines** — the agent that actually
does the work. You pick per session; the rest of Flow (issues, PRs, the web app,
auto mode) is identical either way.

| Engine | What it is | Best for |
|---|---|---|
| **Claude Agent** *(default)* | Drives **Claude Code** as a subprocess — Anthropic's shipped tools, agent loop, and context management. Works on **any repository**, `project.yaml` or not. | Arbitrary repos, especially ones without a Flow manifest. |
| **Builtin** *(fallback)* | Flow's original agentic loop (the frontline/expert/interpreter system in the [README](../README.md)). Needs a `project.yaml` manifest to know how to build and test the repo. | Repositories set up for Flow, where you want the original loop instead of the Claude Agent engine. |
| **Leader/Assistant** *(fallback)* | Flow's own leader/assistant loop: a capable-tier leader that delegates bounded turns to a cheap-tier, tool-calling assistant (see [`m3-demo-runbook.md`](m3-demo-runbook.md)). Positioned to eventually supersede reliance on the vendor-dependent Claude Agent engine ([M4](m4-demo-runbook.md)). Needs a `project.yaml` manifest to know how to build and test the repo. | Repositories set up for Flow, where you want to try the leader/assistant loop instead of Claude Agent. |

`builtin` and `leader` remain fully selectable indefinitely. M3-12 briefly flipped
the default to `leader`; #257 reverted it back to `claude` — that revert (the
`FLOW_WORKER_ENGINE` default mapping in `cli/main.kt`) only changes what an
unspecified selection resolves to, it doesn't remove either fallback engine.

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

### `FLOW_WORKER_ENGINE` — pointing a worker at one engine (M3-09/M3-11/M3-12)

`ENGINE_LEADER` (see the sibling engines above) has no session-creation UI/label
support yet — that's a later story. Until then, `FLOW_WORKER_ENGINE` is how an
operator points a whole worker's unspecified-engine sessions at one engine for a
manual end-to-end run, without a session UI/label to drive the choice per-session:
every session that worker claims with no engine chosen (`ENGINE_UNSPECIFIED` — the
vast majority, since there's no UI to pick otherwise) runs on the requested engine.
Sessions that *do* pin an engine explicitly (`ENGINE_CLAUDE`, or an explicit
`ENGINE_BUILTIN`/`ENGINE_LEADER`) are unaffected either way.

`FLOW_WORKER_ENGINE` accepts `claude` (**the default**, equivalent to leaving it
unset — M3-12 briefly made `leader` the default, #257 reverted it), `builtin`,
and `leader` — set it to `builtin` or `leader` to run a worker on one of the
fallback engines instead.

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
