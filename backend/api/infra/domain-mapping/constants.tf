locals {
  # Domain — defined in infra/common so the host this root publishes and the API_URL that CI/CD
  # hands the service can never drift apart. Prod resolves to exactly the same
  # `api.flow-baseline.medusa.software` as before; staging gets its own subdomain for free.
  flow_api_subdomain_name = module.common.api_subdomain_name
  flow_api_host_name      = module.common.api_host_name
}
