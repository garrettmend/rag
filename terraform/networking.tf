data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  resource_tags = {
    Component = "rag"
  }
}

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.resource_tags, {
    Name = format("%s-vpc", var.project_name)
  })
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = merge(local.resource_tags, {
    Name = format("%s-igw", var.project_name)
  })
}

resource "aws_subnet" "public" {
  count = 2

  vpc_id                  = aws_vpc.main.id
  cidr_block              = format("10.0.%d.0/24", count.index)
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = merge(local.resource_tags, {
    Name = format("%s-public-%d", var.project_name, count.index + 1)
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = merge(local.resource_tags, {
    Name = format("%s-public-rt", var.project_name)
  })
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "app" {
  name        = format("%s-app-sg", var.project_name)
  description = "Inbound RAG API traffic and all outbound traffic"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "Spring Boot API"
    from_port   = var.container_port
    to_port     = var.container_port
    protocol    = "tcp"
    cidr_blocks = var.app_ingress_cidr_blocks
  }

  egress {
    description = "Database, Bedrock, SQS, image pulls, and logs"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.resource_tags, {
    Name = format("%s-app-sg", var.project_name)
  })
}

resource "aws_security_group" "db" {
  name        = format("%s-db-sg", var.project_name)
  description = "PostgreSQL traffic from the RAG ECS task only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from app task"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    description = "Allow RDS response traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.resource_tags, {
    Name = format("%s-db-sg", var.project_name)
  })
}
