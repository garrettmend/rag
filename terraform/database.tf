resource "aws_db_subnet_group" "main" {
  name       = format("%s-db-subnet-group", var.project_name)
  subnet_ids = aws_subnet.public[*].id

  tags = merge(local.resource_tags, {
    Name = format("%s-db-subnet-group", var.project_name)
  })
}

resource "aws_db_instance" "main" {
  identifier = format("%s-db", var.project_name)

  engine                     = "postgres"
  engine_version             = var.db_engine_version
  auto_minor_version_upgrade = true
  instance_class             = var.db_instance_class

  allocated_storage = var.db_allocated_storage_gib
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = "rag_admin"

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false

  backup_retention_period = 0
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = var.skip_final_snapshot

  tags = merge(local.resource_tags, {
    Name = format("%s-db", var.project_name)
  })
}

resource "random_password" "app_db_password" {
  length  = 24
  special = false
}

resource "random_password" "worker_db_password" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "app_db" {
  name        = format("%s-app-db-credentials", var.project_name)
  description = "Credentials for the RLS-scoped RAG application database role."

  tags = local.resource_tags
}

resource "aws_secretsmanager_secret_version" "app_db" {
  secret_id = aws_secretsmanager_secret.app_db.id
  secret_string = jsonencode({
    username = "rag_app"
    password = random_password.app_db_password.result
  })
}

resource "aws_secretsmanager_secret" "worker_db" {
  name        = format("%s-worker-db-credentials", var.project_name)
  description = "Credentials for the cross-tenant RAG ingestion worker database role."

  tags = local.resource_tags
}

resource "aws_secretsmanager_secret_version" "worker_db" {
  secret_id = aws_secretsmanager_secret.worker_db.id
  secret_string = jsonencode({
    username = "rag_worker"
    password = random_password.worker_db_password.result
  })
}
