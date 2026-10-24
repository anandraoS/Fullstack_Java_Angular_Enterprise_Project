output "eks_cluster_name" {
  value = aws_eks_cluster.this.name
}

output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.svc : k => r.repository_url }
}

output "rds_endpoint" {
  value = aws_db_instance.postgres.address
}

output "rds_master_secret_arn" {
  description = "Secrets Manager ARN holding the generated RDS master credentials."
  value       = aws_db_instance.postgres.master_user_secret[0].secret_arn
}

output "redis_primary_endpoint" {
  value = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "msk_bootstrap_brokers_iam" {
  value = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "app_irsa_role_arn" {
  description = "Annotate the oms-app ServiceAccount with this role ARN."
  value       = aws_iam_role.app.arn
}

output "amp_remote_write_url" {
  value = "${aws_prometheus_workspace.this.prometheus_endpoint}api/v1/remote_write"
}
