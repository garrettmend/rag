# Deploying the RAG backend

This directory deploys the existing Spring Boot API and its scheduled ingestion
worker as one ECS Fargate task. It creates an ECR repository for each image,
RDS PostgreSQL 16, SQS plus a dead-letter queue, VPC/security groups, IAM
roles, and CloudWatch logs.

The task includes a short-lived database-migrate sidecar. Before the app
container starts, it creates the vector extension, tables, indexes,
least-privileged database roles, and RLS policies from
[infra/sql/001_initial_schema.sql](../infra/sql/001_initial_schema.sql).
The SQL is idempotent, so a replacement task can safely run it again.

## Before applying

Install and configure the AWS CLI, Docker, and Terraform. In Amazon Bedrock,
complete the Anthropic first-time-use/Marketplace requirements for Claude
Haiku 4.5 in the target account.

The default chat model is the US system inference profile:

~~~text
us.anthropic.claude-haiku-4-5-20251001-v1:0
~~~

It is the value used by the repository's application.yml and is required for
on-demand Bedrock Runtime/Converse calls; do not replace it with the pasted
us.anthropic.claude-sonnet-5 value.

This is intentionally a low-cost demo topology: tasks receive a public IP,
there is no NAT gateway or load balancer, and the database is private behind
the task security group. Before applying, restrict the public API to your own
IP in terraform.tfvars:

~~~hcl
app_ingress_cidr_blocks = ["203.0.113.45/32"]
~~~

The generated app and worker passwords are stored in Secrets Manager, but
their Terraform inputs still exist in Terraform state. Keep state encrypted
and access-controlled; configure a remote backend before using this beyond a
short-lived demo.

## Deploy from PowerShell

From the repository root:

~~~powershell
Set-Location terraform
terraform init
terraform apply
~~~

The first apply creates ECR repositories and an ECS service, but its task
cannot start until both images have been pushed. Build and push them:

~~~powershell
$appRepo = terraform output -raw app_ecr_repository_url
$migrationRepo = terraform output -raw migrations_ecr_repository_url
$registry = $appRepo.Split('/')[0]
$region = terraform output -raw aws_region

aws ecr get-login-password --region $region | docker login --username AWS --password-stdin $registry
docker build --platform linux/amd64 --file ..\Dockerfile --tag "$($appRepo):latest" ..
docker push "$($appRepo):latest"
docker build --platform linux/amd64 --file ..\Dockerfile.migrate --tag "$($migrationRepo):latest" ..
docker push "$($migrationRepo):latest"
~~~

Start a fresh task revision after the images are available:

~~~powershell
$cluster = terraform output -raw ecs_cluster_name
$service = terraform output -raw ecs_service_name

aws ecs update-service --cluster $cluster --service $service --force-new-deployment
aws ecs wait services-stable --cluster $cluster --services $service
~~~

Watch both the migration and application logs if the service does not become
stable:

~~~powershell
$logGroup = terraform output -raw cloudwatch_log_group_name
aws logs tail $logGroup --follow
~~~

## Reach the demo task

There is deliberately no load balancer, so the Fargate public IP is ephemeral.
After a deployment, retrieve it as follows:

~~~powershell
$taskArn = aws ecs list-tasks --cluster $cluster --service-name $service --query 'taskArns[0]' --output text
$eniId = aws ecs describe-tasks --cluster $cluster --tasks $taskArn --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value | [0]" --output text
$publicIp = aws ec2 describe-network-interfaces --network-interface-ids $eniId --query 'NetworkInterfaces[0].Association.PublicIp' --output text

"http://$($publicIp):8080/"
~~~

Open the resulting URL, upload a text file, and use the generated tenant UUID
in the included UI. Direct public-IP access is HTTP-only and changes each time
ECS replaces the task.

## Tear down

This configuration deliberately skips a final RDS snapshot and removes images
from ECR when terraform destroy runs:

~~~powershell
terraform destroy
~~~

For a non-demo deployment, use private subnets with NAT or VPC endpoints, an
ALB with TLS and health checks, a protected remote state backend, RDS backups
and deletion protection, a dedicated migration pipeline, and a stable
application endpoint.
