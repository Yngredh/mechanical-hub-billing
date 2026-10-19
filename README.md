# mechanical-hub-billing
Microsserviço responsável por calcular o orçamento e executar os pagamentos.

## Ambiente local

`docker compose up -d` sobe PostgreSQL 16, LocalStack (filas FIFO e DLQs criadas em `localstack/init-queues.sh`) e WireMock simulando o Mercado Pago (`wiremock/mappings`, porta 8089).

Configuração por variáveis de ambiente (ver `.env.example`). Secrets: `MP_ACCESS_TOKEN` e `MP_WEBHOOK_SECRET`. Sem chave AWS (na nuvem usa `LabRole`).

## Responsabilidade

Calcula o orçamento (mão de obra por reparo + materiais, com o preço congelado em `budget_items`), cria a cobrança no Mercado Pago, confirma o pagamento pelo webhook e detecta o vencimento (job a cada 5 min). Não guarda dados do cliente, status da ordem, estoque nem estado da saga.

## Papel na saga

Participante da saga coreografada pelo `mechanical-hub-orchestrator`, só por SQS FIFO (`MessageGroupId = orderId`, `MessageDeduplicationId = eventId`):

| Direção | Mensagem |
|---|---|
| Consome (`mechanical-hub-billing-commands.fifo`) | `generateBudget` (`repairs`, `reservedMaterials[{materialId, quantity}]`), `generatePaymentLink` (`budgetId`, `orderCost`) |
| Publica (`mechanical-hub-orchestrator-events.fifo`, via outbox) | `budgetGenerated`, `paymentLinkGenerated`, `paymentConfirmed`, `paymentFailed` (`EXPIRED`/`FAILED`) |

Comandos são idempotentes por `{transactionId}:{STEP}` (`inbox_events`); erro transitório faz retry, permanente vai para a DLQ.

## Integração com o Mercado Pago

- Orders API (`POST /v1/orders`, `type=online`, `processing_mode=manual`, boleto excluído), checkout via Checkout Pro; `X-Idempotency-Key = {transactionId}:GENERATE_PAYMENT_LINK`.
- `due_at` = criação + 24h; `expiration_time` na order = +24h05.
- `POST /webhooks/payment` (público) valida `x-signature` (HMAC SHA-256 com `MP_WEBHOOK_SECRET`), consulta a order e, se `processed` + `accredited`, aprova o pagamento e emite `paymentConfirmed`.
- O job de vencimento cancela a order (`X-Idempotency-Key = {transactionId}:CANCEL_MP_ORDER`) e decide entre pago, expirado ou falho.
- Timeouts: conexão 2 s, leitura 5 s, sem retry no cliente.

## Como rodar

```bash
cp .env.example .env     # ajuste se necessário
docker compose up -d     # PostgreSQL, LocalStack (filas) e WireMock (Mercado Pago)
./mvnw spring-boot:run
```

Testes e cobertura (Docker ativo, por causa do Testcontainers): `./mvnw -q verify`. Relatório JaCoCo em `target/site/jacoco/index.html`; cobertura medida: ~91% de instruções (mínimo exigido: 80%).

Prazo reduzido para demonstração: `BILLING_PAYMENT_DUE_DELAY=PT30M` (ISO-8601; padrão `PT24H`). Altera `due_at`/`expires_at` dos novos pagamentos; o job continua a cada 5 min. Não use em produção.

A collection Postman está em `postman/mechanical-hub-billing.postman_collection.json` (webhook, actuator e corpos dos comandos).

## Como testar no sandbox do Mercado Pago

1. No painel, crie a aplicação Checkout Pro, copie o access token `TEST-` para `MP_ACCESS_TOKEN` e a chave secreta do webhook para `MP_WEBHOOK_SECRET`; crie usuários de teste (vendedor e comprador).
2. Aponte `MP_BASE_URL=https://api.mercadopago.com` e configure no painel a URL do webhook (output `webhook_url` do `mechanical-hub-auth`, ou um túnel para `/webhooks/payment` localmente) a cada recriação do ambiente.
3. Envie `generateBudget` e `generatePaymentLink` na fila de comandos (ou deixe o orquestrador enviar) e abra o `paymentLink` retornado em `paymentLinkGenerated`.
4. Pague com o comprador de teste: titular `APRO` aprova (`paymentConfirmed`), `OTHE` é recusado.
5. Expiração: suba com `BILLING_PAYMENT_DUE_DELAY` curto, não pague e aguarde o job: `paymentFailed` com `reason=EXPIRED`.
