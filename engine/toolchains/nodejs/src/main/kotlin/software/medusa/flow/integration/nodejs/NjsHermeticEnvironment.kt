package software.medusa.flow.integration.nodejs

/**
 * The environment to hand a Node.js subprocess (a package-manager install, or a resolved project
 * command such as a test/lint step): the host's environment minus the worker's own
 * credential-brokering vars.
 *
 * The worker container runs under `workload run` with Beacon's GCE-shaped metadata server env
 * (`GCE_METADATA_HOST`/`_IP`/`_ROOT`) pointing at the worker's own identity broker. Left inherited,
 * a repo test/build step that resolves Google ADC (`ComputeEngineCredentials`, `google-auth`, ...)
 * resolves against the worker's Beacon instead of its own hermetic setup — passing in normal CI
 * (where these vars are unset) and failing only inside the worker. Everything else (`PATH`, `HOME`,
 * `JAVA_HOME`, npm/yarn config, ...) is passed through unchanged: the toolchain still needs the
 * full ambient environment to run, only the credential-brokering vars are worker-internal.
 */
internal fun njsHermeticEnvironment(
    lookup: () -> Map<String, String> = System::getenv,
): Map<String, String> = lookup().filterKeys { name -> !name.startsWith("GCE_METADATA_") }
