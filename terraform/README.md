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

This is intentionally a low-cost demo topology (~$22/month): the task runs on
Fargate Spot at 0.25 vCPU and receives a public IP, there is no NAT gateway or
load balancer, and the database is private behind the task security group.
Instead of a load balancer, a short-lived dns-update container in each task
points a free DuckDNS hostname at that task's public IP, so the URL stays the
same across redeploys and Spot replacements.

Create a DuckDNS subdomain at https://www.duckdns.org and store its token in
SSM Parameter Store. It is created outside Terraform so the token never lands
in Terraform state:

~~~powershell
aws ssm put-parameter --name /rag-backend/duckdns-token --type SecureString --tier Standard --value <duckdns-token>
~~~

Set duckdns_domain in terraform.tfvars if your subdomain isn't garrett-rag.

Optionally restrict the public API to your own IP in terraform.tfvars:

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

# Windows PowerShell 5.1 corrupts piped passwords, so pass the token directly.
docker login --username AWS --password (aws ecr get-login-password --region $region) $registry
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

## Reach the demo

~~~powershell
terraform output -raw app_url   # http://garrett-rag.duckdns.org:8080/
~~~

Open the URL, upload a text file, and ask questions about it in the included
UI. Access is HTTP-only.

Each new task updates DuckDNS once the app responds on localhost, so expect
1-3 minutes of downtime per redeploy or Spot replacement, plus up to a minute
of DNS caching. If the hostname stops resolving to the running task, check the
dns-update logs:

~~~powershell
aws logs tail $logGroup --since 30m --log-stream-name-prefix dns
~~~

A response of OK means DuckDNS accepted the update; KO usually means the token
in /rag-backend/duckdns-token is wrong.

## Tear down

This configuration deliberately skips a final RDS snapshot and removes images
from ECR when terraform destroy runs:

~~~powershell
terraform destroy
aws ssm delete-parameter --name /rag-backend/duckdns-token
~~~

For a non-demo deployment, use on-demand Fargate in private subnets with NAT
or VPC endpoints, an ALB with TLS and health checks in place of DuckDNS, a protected remote state backend, RDS backups
and deletion protection, a dedicated migration pipeline, and a stable
application endpoint.
