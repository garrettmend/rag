output "app_urls" {
  description = "Stable public URLs (one per NLB Elastic IP) for the RAG API."
  value       = [for eip in aws_eip.nlb : format("http://%s/", eip.public_ip)]
}

output "app_ecr_repository_url" {
  description = "Push the Spring Boot application image to this repository."
  value       = aws_ecr_repository.app.repository_url
}

output "aws_region" {
  description = "AWS Region configured for this stack."
  value       = var.aws_region
}

output "migrations_ecr_repository_url" {
  description = "Push the database migration image to this repository."
  value       = aws_ecr_repository.migrations.repository_url
}

output "rds_endpoint" {
  description = "Private RDS hostname used by the ECS task."
  value       = aws_db_instance.main.address
}

output "rds_master_secret_arn" {
  description = "RDS-managed master credential secret ARN. Do not expose its value."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "app_db_secret_arn" {
  description = "Secret ARN containing the app role's database credentials."
  value       = aws_secretsmanager_secret.app_db.arn
}

output "worker_db_secret_arn" {
  description = "Secret ARN containing the worker role's database credentials."
  value       = aws_secretsmanager_secret.worker_db.arn
}

output "sqs_queue_url" {
  description = "Ingestion queue URL injected into the application."
  value       = aws_sqs_queue.ingestion.url
}

output "sqs_dead_letter_queue_url" {
  description = "Queue that receives repeatedly failed ingestion messages."
  value       = aws_sqs_queue.ingestion_dlq.url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  value = aws_ecs_service.app.name
}

output "cloudwatch_log_group_name" {
  value = aws_cloudwatch_log_group.app.name
}

output "bedrock_chat_model_id" {
  description = "The actual Bedrock inference-profile ID passed to the deployed application."
  value       = var.bedrock_chat_model_id
}
