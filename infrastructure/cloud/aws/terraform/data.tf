# Managed data tier: RDS PostgreSQL (one instance, four databases created by a migration job/bootstrap),
# ElastiCache Redis (rate limiting only), MSK (Kafka). All encrypted at rest and in transit, reachable only from EKS.

locals {
  eks_sg = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

# ------------------------------------------------------------------ PostgreSQL
resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-${var.environment}"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.name}-rds-"
  vpc_id      = aws_vpc.this.id
  description = "PostgreSQL from EKS only"
  ingress {
    description     = "postgres from EKS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [local.eks_sg]
  }
}

resource "aws_db_instance" "postgres" {
  identifier                          = "${var.name}-${var.environment}"
  engine                              = "postgres"
  engine_version                      = "16"
  instance_class                      = var.db_instance_class
  allocated_storage                   = var.db_allocated_storage
  max_allocated_storage               = var.db_allocated_storage * 4
  storage_type                        = "gp3"
  storage_encrypted                   = true
  db_name                             = "postgres"
  username                            = "oms_admin"
  manage_master_user_password         = true # RDS generates the password and stores it in Secrets Manager: never in code/state
  multi_az                            = true
  db_subnet_group_name                = aws_db_subnet_group.this.name
  vpc_security_group_ids              = [aws_security_group.rds.id]
  publicly_accessible                 = false
  backup_retention_period             = 14
  deletion_protection                 = true
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.name}-${var.environment}-final"
  iam_database_authentication_enabled = true
  performance_insights_enabled        = true
  auto_minor_version_upgrade          = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]
}

# ------------------------------------------------------------------ Redis
resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-${var.environment}"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_security_group" "redis" {
  name_prefix = "${var.name}-redis-"
  vpc_id      = aws_vpc.this.id
  description = "Redis from EKS only"
  ingress {
    description     = "redis from EKS"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [local.eks_sg]
  }
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${var.name}-${var.environment}"
  description                = "OMS rate-limit bucket store"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.redis_auth_token
}

# ------------------------------------------------------------------ MSK (Kafka)
resource "aws_security_group" "msk" {
  name_prefix = "${var.name}-msk-"
  vpc_id      = aws_vpc.this.id
  description = "Kafka from EKS only"
  ingress {
    description     = "kafka SASL/IAM (9098 only) from EKS"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [local.eks_sg]
  }
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-${var.environment}"
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = var.az_count

  broker_node_group_info {
    instance_type   = var.msk_instance_type
    client_subnets  = aws_subnet.data[*].id
    security_groups = [aws_security_group.msk.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.msk_broker_volume_gb
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-${var.environment}"
  kafka_versions = [var.msk_kafka_version]
  # Production defaults per contract: replication 3, min ISR 2, topics created by the topic job (no auto-create).
  server_properties = <<-PROPS
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=3
  PROPS
}
