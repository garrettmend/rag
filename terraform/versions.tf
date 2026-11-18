terraform {
  required_version = ">= 1.10"

  # Created outside Terraform (see README). Versioned and encrypted; S3-native
  # locking needs Terraform 1.10+.
  backend "s3" {
    bucket       = "rag-backend-tfstate-682595228339"
    key          = "rag-backend/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      ManagedBy = "Terraform"
      Project   = var.project_name
    }
  }
}
