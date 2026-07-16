# Reconcile monitoring (alerting-lite)

The reconciler is driven by two triggers — the GitHub webhook (latency) and the
Cloud Scheduler job `api-reconcile` every ~3 min (correctness backstop, see
`gcp-scheduler.tf`). Each reconcile pass logs a structured start/summary line
(story 05, `Reconciler.kt`), so "is the backstop alive?" is a log query, not a
metric we have to emit.

Full alerting is out of scope for M2; this documents the query to build a
log-based alert on when we want one.

## "No successful reconcile in 15 min"

The scheduler fires every 3 min, so five consecutive misses ≈ 15 min is a clear
outage signal. In **Logs Explorer** (Cloud Run service `api`):

```
resource.type="cloud_run_revision"
resource.labels.service_name="api"
jsonPayload.message=~"reconcile start"
```

(Match whatever field the logging encoder puts the message in — `jsonPayload`
for structured JSON logs, `textPayload` for the plain-text encoder. Adjust the
`=~"reconcile start"` substring to the exact log line in `Reconciler.reconcile`.)

To turn it into an alert:

1. **Logs Explorer → Create log-based metric** (counter) on the query above,
   e.g. `reconcile_started`.
2. **Monitoring → Alerting → Create policy** on that metric with the condition
   *"count < 1 over a 15-minute rolling window"* (absence of reconciles).

## Related signals worth a query when investigating

- Reconcile *outcomes*: the per-repo summary line (picked / advanced / drained
  counts) from `Reconciler`.
- Webhook rejections: `rejected GitHub webhook` warnings from
  `GitHubWebhookService` — a spike means signature/secret drift.
- Cloud Scheduler delivery failures: the `api-reconcile` job's own
  `cloud_scheduler_job` logs (non-2xx responses from the API).
