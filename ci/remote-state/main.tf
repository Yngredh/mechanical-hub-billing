# =============================================================================
# Leitura dos states remotos — somente leitura
# =============================================================================
#
# Nao provisiona nada: resolve, em um `terraform init` + `terraform apply` sem
# recursos, os valores que os repositorios de infraestrutura publicam como
# contrato para o pipeline do billing:
#
#   mechanical-hub-infra     -> eks_cluster_name, ecr_repository_urls["billing"],
#                               sqs_queue_urls / sqs_dlq_urls, otlp_http_endpoint
#   mechanical-hub-database  -> databases["billing"]
#
# Sem bloco `backend` de proposito: o state deste diretorio e efemero e local
# ao runner (so contem os data sources).
#
# Uso:
#   terraform init -input=false
#   terraform apply -auto-approve -input=false \
#     -var="state_bucket=mechanical-hub-tfstate-<conta>"
#   terraform output -raw eks_cluster_name

terraform {
  required_version = ">= 1.7.0"
}

variable "state_bucket" {
  description = "Bucket S3 que hospeda os states dos repositorios de infraestrutura."
  type        = string

  validation {
    condition     = length(var.state_bucket) > 0
    error_message = "state_bucket e obrigatorio (ex.: mechanical-hub-tfstate-<conta>)."
  }
}

variable "state_region" {
  description = "Regiao do bucket de state."
  type        = string
  default     = "us-east-1"
}

variable "infra_state_key" {
  description = "Chave do state do mechanical-hub-infra dentro do bucket."
  type        = string
  default     = "mechanical-hub-infra/terraform.tfstate"
}

variable "database_state_key" {
  description = "Chave do state do mechanical-hub-database dentro do bucket."
  type        = string
  default     = "mechanical-hub-database/terraform.tfstate"
}

data "terraform_remote_state" "infra" {
  backend = "s3"

  config = {
    bucket = var.state_bucket
    key    = var.infra_state_key
    region = var.state_region
  }
}

data "terraform_remote_state" "database" {
  backend = "s3"

  config = {
    bucket = var.state_bucket
    key    = var.database_state_key
    region = var.state_region
  }
}

locals {
  service  = "billing"
  database = data.terraform_remote_state.database.outputs.databases[local.service]
  queues   = data.terraform_remote_state.infra.outputs.sqs_queue_urls
  dlqs     = data.terraform_remote_state.infra.outputs.sqs_dlq_urls
}

# Se uma chave sumir do repositorio produtor, o apply falha aqui com o nome do
# valor ausente, antes de qualquer deploy contra um contrato quebrado.

output "eks_cluster_name" {
  value = data.terraform_remote_state.infra.outputs.eks_cluster_name
}

output "ecr_repository_url" {
  value = data.terraform_remote_state.infra.outputs.ecr_repository_urls[local.service]
}

output "db_endpoint" {
  value = local.database.endpoint
}

output "db_port" {
  value = local.database.port
}

output "db_name" {
  value = local.database.db_name
}

output "db_username" {
  value = local.database.app_username
}

output "billing_commands_queue_url" {
  value = local.queues["billing-commands"]
}

output "billing_commands_dlq_url" {
  value = local.dlqs["billing-commands"]
}

output "orchestrator_events_queue_url" {
  value = local.queues["orchestrator-events"]
}

# Opcional: null com a observabilidade desligada no mechanical-hub-infra. Ler
# com `terraform output -json` e tratar a ausencia.
output "otlp_http_endpoint" {
  value = try(data.terraform_remote_state.infra.outputs.otlp_http_endpoint, null)
}
