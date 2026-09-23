resource "aws_ecr_repository" "app" {
  name                 = format("%s-app", var.project_name)
  force_delete         = var.ecr_force_delete
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.resource_tags
}

resource "aws_ecr_repository" "migrations" {
  name                 = format("%s-migrations", var.project_name)
  force_delete         = var.ecr_force_delete
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.resource_tags
}

resource "aws_cloudwatch_log_group" "app" {
  name              = format("/ecs/%s", var.project_name)
  retention_in_days = var.log_retention_days

  tags = local.resource_tags
}

resource "aws_ecs_cluster" "main" {
  name = format("%s-cluster", var.project_name)

  tags = local.resource_tags
}

resource "aws_ecs_task_definition" "app" {
  family                   = format("%s-task", var.project_name)
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "database-migrate"
      image     = format("%s:%s", aws_ecr_repository.migrations.repository_url, var.container_image_tag)
      essential = false

      environment = [
        { name = "DB_HOST", value = aws_db_instance.main.address },
        { name = "DB_PORT", value = tostring(aws_db_instance.main.port) },
        { name = "DB_NAME", value = var.db_name },
        { name = "DB_SSLMODE", value = "require" },
      ]

      secrets = [
        { name = "DB_USERNAME", valueFrom = format("%s:username::", aws_db_instance.main.master_user_secret[0].secret_arn) },
        { name = "DB_PASSWORD", valueFrom = format("%s:password::", aws_db_instance.main.master_user_secret[0].secret_arn) },
        { name = "APP_DB_PASSWORD", valueFrom = format("%s:password::", aws_secretsmanager_secret.app_db.arn) },
        { name = "WORKER_DB_PASSWORD", valueFrom = format("%s:password::", aws_secretsmanager_secret.worker_db.arn) },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "migrate"
        }
      }
    },
    {
      name      = "app"
      image     = format("%s:%s", aws_ecr_repository.app.repository_url, var.container_image_tag)
      essential = true

      dependsOn = [
        { containerName = "database-migrate", condition = "SUCCESS" },
      ]

      portMappings = [
        { containerPort = var.container_port, protocol = "tcp", appProtocol = "http" },
      ]

      environment = [
        { name = "APP_DATASOURCE_JDBC_URL", value = format("jdbc:postgresql://%s:%d/%s?sslmode=require", aws_db_instance.main.address, aws_db_instance.main.port, var.db_name) },
        { name = "WORKER_DATASOURCE_JDBC_URL", value = format("jdbc:postgresql://%s:%d/%s?sslmode=require", aws_db_instance.main.address, aws_db_instance.main.port, var.db_name) },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "AWS_BEDROCK_CHAT_MODEL_ID", value = var.bedrock_chat_model_id },
        { name = "AWS_BEDROCK_EMBEDDING_MODEL_ID", value = var.bedrock_embedding_model_id },
        { name = "AWS_SQS_INGESTION_QUEUE_URL", value = aws_sqs_queue.ingestion.url },
      ]

      secrets = [
        { name = "APP_DATASOURCE_USERNAME", valueFrom = format("%s:username::", aws_secretsmanager_secret.app_db.arn) },
        { name = "APP_DATASOURCE_PASSWORD", valueFrom = format("%s:password::", aws_secretsmanager_secret.app_db.arn) },
        { name = "WORKER_DATASOURCE_USERNAME", valueFrom = format("%s:username::", aws_secretsmanager_secret.worker_db.arn) },
        { name = "WORKER_DATASOURCE_PASSWORD", valueFrom = format("%s:password::", aws_secretsmanager_secret.worker_db.arn) },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "app"
        }
      }
    },
  ])

  tags = local.resource_tags
}

resource "aws_ecs_service" "app" {
  name             = format("%s-service", var.project_name)
  cluster          = aws_ecs_cluster.main.id
  task_definition  = aws_ecs_task_definition.app.arn
  desired_count    = var.desired_count
  launch_type      = "FARGATE"
  platform_version = "1.4.0"

  # Covers the migration sidecar plus ~35s Spring Boot startup.
  health_check_grace_period_seconds = 120

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = var.container_port
  }

  # The public IP is still needed for outbound access (ECR, Bedrock, SQS) since
  # there is no NAT gateway; inbound is limited to the NLB security group.
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }

  tags = local.resource_tags
}
