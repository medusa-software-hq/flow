# flow

`flow` is a monorepo for building and running task-graph based workflows.

At the center of the project is a Kotlin core service that stores workflow sessions, validates and updates task graphs, starts runs, and reports per-task execution progress over gRPC. A React-based console web app consumes that API so users can create sessions, edit task graphs visually, and monitor running work.

## Repository layout

- `services/core/code`: Kotlin/Gradle backend modules for the core service, worker runtime, Git helpers, and OpenCode integration.
- `apps/console-web/code/frontend`: React 19 + Vite frontend for the workflow console.
- `proto`: Protobuf definitions for the control-service API shared between backend and frontend.
- `infra`: Terraform and project-level infrastructure configuration.
- `Taskfile.yml`: Top-level tasks for formatting, linting, and analysis across the repo.

## What this project does

- Models workflows as directed task graphs.
- Persists workflow sessions in draft or running states.
- Exposes gRPC endpoints to list, create, update, validate, and start sessions.
- Tracks execution progress for running tasks.
- Provides a browser UI for managing sessions and editing task graphs.
