variable "aws_region" {
  description = "AWS Region in which to create the demo stack."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix for AWS resource names."
  type        = string
  default     = "rag-backend"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}$", var.project_name))
    error_message = "project_name must be 2-29 lowercase letters, numbers, or hyphens and start with a letter."
  }
}

variable "db_name" {
  description = "Initial PostgreSQL database name."
  type        = string
  default     = "ragdb"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{0,62}$", var.db_name))
    error_message = "db_name must start with a lowercase letter and contain only lowercase letters and numbers."
  }
}

variable "db_engine_version" {
  description = "PostgreSQL engine version or major-version prefix. Keeping this at 16 accepts the latest supported PostgreSQL 16 minor version."
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is intended only for a short-lived demo."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage_gib" {
  description = "Initial gp3 database storage in GiB."
  type        = number
  default     = 20
}

variable "container_port" {
  description = "Port exposed by the Spring Boot container."
  type        = number
  default     = 8080
}

variable "app_ingress_cidr_blocks" {
  description = "CIDRs allowed to reach the task's public IP. Restrict this to your own public IP before sharing the deployment."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "desired_count" {
  description = "Number of combined API/ingestion-worker tasks to run."
  type        = number
  default     = 1
}

variable "task_cpu" {
  description = "Fargate task CPU units. 256 is the cheapest size; Spring Boot starts slower but runs fine for a demo."
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 1024
}

variable "duckdns_domain" {
  description = "DuckDNS subdomain (without .duckdns.org) that each new task points at its public IP."
  type        = string
  default     = "garrett-rag"
}

variable "duckdns_token_parameter_name" {
  description = "SSM SecureString parameter holding the DuckDNS token. Create it outside Terraform so the token stays out of state."
  type        = string
  default     = "/rag-backend/duckdns-token"
}

variable "container_image_tag" {
  description = "Tag to use for both ECR images."
  type        = string
  default     = "latest"
}

variable "bedrock_chat_model_id" {
  description = "Bedrock Runtime inference-profile ID passed to Converse and ConverseStream."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "bedrock_embedding_model_id" {
  description = "Bedrock embedding model used by the application."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "sqs_visibility_timeout_seconds" {
  description = "How long an ingestion message remains invisible while the worker calls Bedrock."
  type        = number
  default     = 300
}

variable "sqs_max_receive_count" {
  description = "Failed delivery attempts before an ingestion message moves to the dead-letter queue."
  type        = number
  default     = 3
}

variable "log_retention_days" {
  description = "CloudWatch log retention for the application and migration containers."
  type        = number
  default     = 14
}

variable "deletion_protection" {
  description = "Protect the RDS instance from deletion. Keep false only for disposable environments."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "Skip the final RDS snapshot on destroy. Keep true only for disposable environments."
  type        = bool
  default     = true
}

variable "ecr_force_delete" {
  description = "Delete ECR images when Terraform destroys this demo stack."
  type        = bool
  default     = true
}
