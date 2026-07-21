package software.medusa.flow.systemtest

import org.junit.jupiter.api.Tag

/**
 * The two system-test tiers (M5). A test carries exactly one; the promotion gate runs each tier as
 * its own step (`-PsystemTestTags=smoke` / `loop`) so "smoke green, loop red" is readable at a
 * glance.
 *
 * - [Smoke] — parity with the old bash smoke suite: fast, stateless post-deploy checks that need
 *   **no** live worker (a worker outage must never block promoting an API-only fix). Story 02.
 * - [Loop] — the full real cycle through staging's admin-run worker: needs the standing "a worker
 *   is alive" assumption and real model credits, budget-capped. Story 03. Guarded by the
 *   worker-liveness preflight so a worker outage reads as a distinct "staging worker down" rather
 *   than a generic timeout.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("smoke")
annotation class Smoke

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("loop")
annotation class Loop
