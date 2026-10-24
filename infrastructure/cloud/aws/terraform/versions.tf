terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Remote state: configure per environment, never commit state or backend credentials.
  # backend "s3" {
  #   bucket         = "<state-bucket>"
  #   key            = "oms/terraform.tfstate"
  #   region         = "eu-west-1"
  #   dynamodb_table = "<lock-table>"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "oms"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
