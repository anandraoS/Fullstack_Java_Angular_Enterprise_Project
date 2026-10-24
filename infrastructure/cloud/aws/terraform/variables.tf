variable "region" {
  description = "AWS region."
  type        = string
  default     = "eu-west-1"
}

variable "environment" {
  description = "Environment name (dev, test, prod)."
  type        = string
  default     = "prod"
}

variable "name" {
  description = "Name prefix for all resources."
  type        = string
  default     = "oms"
}

variable "vpc_cidr" {
  description = "VPC CIDR."
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones (subnets per tier)."
  type        = number
  default     = 3
}

variable "single_nat_gateway" {
  description = "Use one NAT gateway (cheaper, not AZ-resilient). Set false for prod."
  type        = bool
  default     = false
}

variable "services" {
  description = "Container images / ECR repositories."
  type        = list(string)
  default     = ["order-service", "inventory-service", "fulfillment-service", "notification-service", "api-gateway", "oms-ui"]
}

variable "eks_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.31"
}

variable "eks_public_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint. Empty list disables the public endpoint (private only)."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Managed node group instance types."
  type        = list(string)
  default     = ["m6i.large"]
}

variable "node_desired_size" {
  type    = number
  default = 3
}

variable "node_min_size" {
  type    = number
  default = 3
}

variable "node_max_size" {
  type    = number
  default = 9
}

variable "db_instance_class" {
  type    = string
  default = "db.m6g.large"
}

variable "db_allocated_storage" {
  type    = number
  default = 50
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

variable "redis_auth_token" {
  description = "Redis AUTH token (16-128 chars). Supply via TF_VAR_redis_auth_token from a secret store; never put it in a tfvars file in git."
  type        = string
  sensitive   = true
}

variable "msk_kafka_version" {
  type    = string
  default = "3.6.0"
}

variable "msk_instance_type" {
  type    = string
  default = "kafka.m5.large"
}

variable "msk_broker_volume_gb" {
  type    = number
  default = 100
}
