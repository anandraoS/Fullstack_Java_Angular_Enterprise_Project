# Secrets Manager: containers only. VALUES are set out-of-band (console/CLI/rotation lambda) so they never reach
# Terraform state, and are synced into Kubernetes by External Secrets (infrastructure/kubernetes/examples).
resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${var.name}/${var.environment}/jwt"
  description             = "OMS JWT signing key (JSON: {\"secret\": \"...\"}); >= 32 bytes"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret" "redis" {
  name                    = "${var.name}/${var.environment}/redis"
  description             = "OMS Redis AUTH (JSON: {\"password\": \"...\"}); must equal var.redis_auth_token"
  recovery_window_in_days = 7
}

# Amazon Managed Service for Prometheus (metrics sink; scrape with an in-cluster Prometheus/ADOT remote_write).
resource "aws_prometheus_workspace" "this" {
  alias = "${var.name}-${var.environment}"
}

# Amazon Managed Grafana (SSO-authenticated; add data sources and dashboards from observability/grafana).
data "aws_iam_policy_document" "grafana_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["grafana.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "grafana" {
  name               = "${var.name}-${var.environment}-grafana"
  assume_role_policy = data.aws_iam_policy_document.grafana_assume.json
}

resource "aws_grafana_workspace" "this" {
  name                     = "${var.name}-${var.environment}"
  account_access_type      = "CURRENT_ACCOUNT"
  authentication_providers = ["AWS_SSO"]
  permission_type          = "SERVICE_MANAGED"
  role_arn                 = aws_iam_role.grafana.arn
  data_sources             = ["PROMETHEUS", "CLOUDWATCH", "XRAY"]
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/${var.name}/${var.environment}/app"
  retention_in_days = 30
}

# Per-service database credentials referenced by infrastructure/kubernetes/examples/externalsecret.example.yaml
# (keys oms/<env>/<service>-db, JSON {"username": "...", "password": "..."}). Containers only: values are created
# out-of-band (per-service DB roles, rotation lambda) so no credential ever reaches Terraform state.
resource "aws_secretsmanager_secret" "service_db" {
  for_each                = toset(["order", "inventory", "fulfillment", "notification"])
  name                    = "${var.name}/${var.environment}/${each.key}-db"
  description             = "OMS ${each.key}-service DB credentials (JSON: {\"username\": \"...\", \"password\": \"...\"})"
  recovery_window_in_days = 7
}
