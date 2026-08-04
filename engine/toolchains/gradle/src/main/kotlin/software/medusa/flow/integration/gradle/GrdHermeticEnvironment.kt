package software.medusa.flow.integration.gradle

/**
 * The environment to hand a Gradle build launched through the Tooling API: the host's environment
 * minus the worker's own credential-brokering vars.
 *
 * The worker container runs under `workload run` with Beacon's GCE-shaped metadata server env
 * (`GCE_METADATA_HOST`/`_IP`/`_ROOT`) pointing at the worker's own identity broker. Left inherited,
 * a repo test that resolves Google ADC (`ComputeEngineCredentials`, `google-auth`, ...) resolves
 * against the worker's Beacon instead of its own hermetic setup — passing in normal CI (where these
 * vars are unset) and failing only inside the worker. Everything else (`PATH`, `HOME`, `JAVA_HOME`,
 * `GRADLE_USER_HOME`, ...) is passed through unchanged: the build still needs the full ambient
 * environment to run, only the credential-brokering vars are worker-internal.
 */
internal fun grdHermeticEnvironment(
    lookup: () -> Map<String, String> = System::getenv,
): Map<String, String> = lookup().filterKeys { name -> !name.startsWith("GCE_METADATA_") }
