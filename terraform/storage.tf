# Persistent storage for the Caddy proxy's certificates and ACME account.
# Fargate task storage is ephemeral, so without this every redeploy or Spot
# replacement requested a brand-new certificate and quickly exhausted Let's
# Encrypt's limit of 5 certificates per hostname per week.
resource "aws_efs_file_system" "caddy" {
  creation_token = format("%s-caddy", var.project_name)
  encrypted      = true

  tags = merge(local.resource_tags, {
    Name = format("%s-caddy", var.project_name)
  })
}

resource "aws_efs_mount_target" "caddy" {
  count = length(aws_subnet.public)

  file_system_id  = aws_efs_file_system.caddy.id
  subnet_id       = aws_subnet.public[count.index].id
  security_groups = [aws_security_group.efs.id]
}
