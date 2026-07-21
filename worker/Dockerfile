# Flow worker image (M4 Path B / story B4). Runs `flow work` — the worker poll loop — as the
# container's main process. Deliberately un-orchestrated: no supervisor, no cron, no init beyond a
# tiny signal forwarder. The worker IS the process, so a `docker stop` / `workload run` SIGTERM
# reaches it and its own JVM shutdown hook runs (cancel the loop, let the in-flight session drain).
#
# Contents, and why each is here:
#   - a JRE                     — runs the worker fat jar
#   - git                       — the worker clones the target repo and pushes the result branch
#   - Node (npm + corepack yarn) — the worker locates `npm` AND `yarn` at startup regardless of task
#                                  (cli/.../main.kt), and the claude CLI is a Node package
#   - the pinned `claude` CLI    — the M4 claude engine drives it as a subprocess
#
# Nothing secret is baked in. Identity comes from the mounted ms-workload config (config.json under
# XDG_CONFIG_HOME); env + secrets come from the profile at spawn; GCP identity comes from Beacon's
# metadata server under `workload run`. See the surface table in worker/README.md.

FROM eclipse-temurin:21-jre-jammy

# Pinned to the claude-engine contract (.github/workflows/check-claude-cli.yml). A build arg so the
# pin can move without editing this file (CI passes the same value; bump both together).
ARG CLAUDE_CLI_VERSION=2.1.214
ARG NODE_MAJOR=22

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends git ca-certificates curl gnupg tini; \
    curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -; \
    apt-get install -y --no-install-recommends nodejs; \
    corepack enable; \
    npm install -g "@anthropic-ai/claude-code@${CLAUDE_CLI_VERSION}"; \
    claude --version; \
    apt-get purge -y --auto-remove gnupg; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*

# Non-root. XDG_CONFIG_HOME is where the mounted ms-workload identity (config.json) is expected, and
# where the worker registered once persists it across restarts (B1: restart must not re-register).
RUN useradd --create-home --uid 10001 flow
ENV XDG_CONFIG_HOME=/home/flow/.config

COPY flow-cli.jar /opt/flow/flow-cli.jar

# Build stamp (M5 story 04). The worker reports this to the control-plane fleet registry so the
# system-test gate can print which build it tested against and warn on version skew. CI passes the
# trunk commit sha (the same value the image is tagged with, `worker:<sha>`); a local `docker build`
# leaves it "unknown". The worker reads it via FLOW_WORKER_VERSION (WrkWorkerIdentity). The image
# *digest* isn't knowable at build time (it's the push output) — the ms-workload profile that pins
# the digest injects FLOW_WORKER_IMAGE_DIGEST at spawn; see worker/README.md.
ARG FLOW_WORKER_VERSION=unknown
ENV FLOW_WORKER_VERSION=$FLOW_WORKER_VERSION

USER flow
WORKDIR /home/flow

# tini as PID 1 forwards SIGTERM/SIGINT to the worker (preserving its own cleanup path) AND reaps the
# git/claude tool subprocesses the worker spawns — a raw JVM as PID 1 would leave orphaned
# grandchildren as zombies over a long-running worker's life.
ENTRYPOINT ["tini", "--", "java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/flow/flow-cli.jar", "work"]
