# Fargate tasks can't hold an Elastic IP, so a Network Load Balancer with one
# EIP per subnet gives the API fixed public addresses that survive redeploys.

resource "aws_eip" "nlb" {
  count  = length(aws_subnet.public)
  domain = "vpc"

  tags = merge(local.resource_tags, {
    Name = format("%s-nlb-eip-%d", var.project_name, count.index + 1)
  })
}

resource "aws_security_group" "nlb" {
  name        = format("%s-nlb-sg", var.project_name)
  description = "Public HTTP traffic to the RAG API load balancer"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = var.app_ingress_cidr_blocks
  }

  egress {
    description = "Forward to ECS tasks"
    from_port   = var.container_port
    to_port     = var.container_port
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.main.cidr_block]
  }

  tags = merge(local.resource_tags, {
    Name = format("%s-nlb-sg", var.project_name)
  })
}

resource "aws_lb" "app" {
  name               = format("%s-nlb", var.project_name)
  load_balancer_type = "network"
  internal           = false
  security_groups    = [aws_security_group.nlb.id]

  # With a single task, only the NLB node in that task's AZ would otherwise have a
  # healthy target, so the other Elastic IP would time out.
  enable_cross_zone_load_balancing = true

  dynamic "subnet_mapping" {
    for_each = aws_subnet.public
    content {
      subnet_id     = subnet_mapping.value.id
      allocation_id = aws_eip.nlb[subnet_mapping.key].id
    }
  }

  tags = local.resource_tags
}

resource "aws_lb_target_group" "app" {
  name        = format("%s-tg", var.project_name)
  port        = var.container_port
  protocol    = "TCP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  # Short drain so redeploys of this single-task demo don't hang for 5 minutes.
  deregistration_delay = 30

  health_check {
    protocol            = "HTTP"
    path                = "/"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }

  tags = local.resource_tags
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "TCP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  tags = local.resource_tags
}
