locals {
  # Domain
  flow_web_subdomain_name = "${module.common.project_base_name}-${module.common.project_variant}"
  flow_web_host_name      = "${local.flow_web_subdomain_name}.${module.common.organization_domain}"
}
