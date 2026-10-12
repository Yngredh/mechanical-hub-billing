# mechanical-hub-billing
Microsserviço responsável por calcular o orçamento e executar os pagamentos.

## Ambiente local

`docker compose up -d` sobe PostgreSQL 16, LocalStack (filas FIFO e DLQs criadas em `localstack/init-queues.sh`) e WireMock simulando o Mercado Pago (`wiremock/mappings`, porta 8089).

Configuração por variáveis de ambiente (ver `.env.example`). Secrets: `MP_ACCESS_TOKEN` e `MP_WEBHOOK_SECRET`. Sem chave AWS (na nuvem usa `LabRole`).
