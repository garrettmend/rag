resource "aws_sqs_queue" "ingestion_dlq" {
  name                      = format("%s-ingestion-dlq", var.project_name)
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

  tags = merge(local.resource_tags, {
    Name = format("%s-ingestion-dlq", var.project_name)
  })
}

resource "aws_sqs_queue" "ingestion" {
  name                       = format("%s-ingestion-queue", var.project_name)
  visibility_timeout_seconds = var.sqs_visibility_timeout_seconds
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.ingestion_dlq.arn
    maxReceiveCount     = var.sqs_max_receive_count
  })

  tags = merge(local.resource_tags, {
    Name = format("%s-ingestion-queue", var.project_name)
  })
}
