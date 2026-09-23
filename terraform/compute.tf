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

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

data "aws_caller_identity" "current" {}

locals {
  # Built from the name rather than read via a data source so the token value
  # never lands in Terraform state.
  duckdns_token_parameter_arn = format("arn:aws:ssm:%s:%s:parameter%s",
  var.aws_region, data.aws_caller_identity.current.account_id, var.duckdns_token_parameter_name)
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
      # Points the DuckDNS hostname at this task's public IP once the app is
      # serving, so the URL survives redeploys and Spot replacements without a
      # load balancer. DuckDNS takes the IP from the request's source address.
      name       = "dns-update"
      image      = format("%s:%s", aws_ecr_repository.migrations.repository_url, var.container_image_tag)
      essential  = false
      entryPoint = ["sh", "-c"]
      command = [format(<<-EOT
        for i in $(seq 1 100); do
          curl -fs -o /dev/null http://localhost:%d/ && break
          sleep 3
        done
        curl -fsS "https://www.duckdns.org/update?domains=$DUCKDNS_DOMAIN&token=$DUCKDNS_TOKEN&ip="
      EOT
      , var.container_port)]

      dependsOn = [
        { containerName = "database-migrate", condition = "SUCCESS" },
      ]

      environment = [
        { name = "DUCKDNS_DOMAIN", value = var.duckdns_domain },
      ]

      secrets = [
        { name = "DUCKDNS_TOKEN", valueFrom = local.duckdns_token_parameter_arn },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "dns"
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
  platform_version = "1.4.0"

  # Fargate Spot is ~70% cheaper; AWS may reclaim the task with two minutes'
  # notice and ECS starts a replacement (which gets a new public IP).
  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
  }

  force_new_deployment = true

  depends_on = [aws_ecs_cluster_capacity_providers.main]

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }

  tags = local.resource_tags
}
