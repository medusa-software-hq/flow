locals {
  # Domain — subdomain_label is per-environment (see infra/common), so prod keeps
  # flow-baseline.medusa.software while staging publishes flow-baseline-staging.medusa.software.
  # Prod resolves to exactly the previous project_base_name-project_variant value.
  flow_web_subdomain_name = module.common.subdomain_label
  flow_web_host_name      = "${local.flow_web_subdomain_name}.${module.common.organization_domain}"
}
