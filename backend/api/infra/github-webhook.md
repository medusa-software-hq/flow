# GitHub webhook setup

The API exposes an unauthenticated-by-gRPC-auth route, `POST /webhook/github`, that
HMAC-verifies each delivery and fires a repo-scoped reconcile. It is a **latency
accelerant only** — with the webhook disabled the system still converges on the
scheduler's cadence (story 11), just slower.

See `GitHubWebhookService.kt` for the verification and dispatch logic.

## One-time configuration

1. **Generate a secret** (any high-entropy string), e.g.:

   ```sh
   openssl rand -hex 32
   ```

2. **Store it in Secret Manager.** Terraform creates the secret *shell*
   (`<api-service>-github-webhook-secret`) with a `placeholder` version and
   grants the Cloud Run SA read access (`gcp-secret-manager.tf`); the real value
   is added out-of-band so Terraform never holds or overwrites it (same pattern
   as the GitHub App PEM):

   ```sh
   printf '%s' "<generated-secret>" \
     | gcloud secrets versions add <api-service>-github-webhook-secret --data-file=-
   ```

   The Cloud Run service reads it as `GITHUB_WEBHOOK_SECRET` (`main.tf`). Until a
   real value is present the route rejects every delivery (401) and the system
   runs on scheduler cadence only.

3. **Configure the GitHub App webhook** (App settings → *Webhook*):
   - **Payload URL:** `https://<api-domain>/webhook/github`
   - **Content type:** `application/json`
   - **Secret:** the same generated value
   - **Events:** subscribe to `issues`, `pull_request`, `workflow_run`,
     `check_suite`, and issue-dependency events if available. (The handler
     ignores the event type and payload beyond `repository.full_name`; the
     subscription set just controls what wakes the reconciler.)

## Verifying / disabling

- A correctly signed delivery returns `202` and triggers a pick/advance within
  seconds. Bad or unsigned requests return `401` and are logged.
- Disabling the webhook in the App settings degrades cleanly to scheduler
  cadence — nothing else changes.
