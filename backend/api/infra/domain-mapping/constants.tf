locals {
  # Domain
  flow_api_subdomain_name = "api.${module.common.project_base_name}-${module.common.project_variant}"
  flow_api_host_name      = "${local.flow_api_subdomain_name}.${module.common.organization_domain}"
}
