data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = format("%s-ecs-execution-role", var.project_name)
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = local.resource_tags
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = format("%s-execution-secrets", var.project_name)
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.app_db.arn,
          aws_secretsmanager_secret.worker_db.arn,
          aws_db_instance.main.master_user_secret[0].secret_arn,
        ]
      },
      {
        # SecureString under the AWS-managed aws/ssm key, so no kms:Decrypt grant is needed.
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = local.duckdns_token_parameter_arn
      },
    ]
  })
}

resource "aws_iam_role" "ecs_task" {
  name               = format("%s-ecs-task-role", var.project_name)
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = local.resource_tags
}

resource "aws_iam_role_policy" "ecs_task_permissions" {
  name = format("%s-task-permissions", var.project_name)
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "InvokeBedrockModels"
        Effect = "Allow"
        Action = [
          "bedrock:InvokeModel",
          "bedrock:InvokeModelWithResponseStream",
          "bedrock:Converse",
          "bedrock:ConverseStream",
        ]
        # Haiku 4.5 is invoked through an inference profile. Narrowing this
        # policy requires every profile and foundation-model ARN it can route to.
        Resource = "*"
      },
      {
        Sid    = "ProcessIngestionMessages"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
        ]
        Resource = aws_sqs_queue.ingestion.arn
      },
    ]
  })
}
