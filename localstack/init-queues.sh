#!/bin/sh
# Cria as filas FIFO e as DLQs (maxReceiveCount = 6) no LocalStack.
set -e

create_queue() {
  name="$1"
  dlq="${name%.fifo}-dlq.fifo"
  awslocal sqs create-queue --queue-name "$dlq" \
    --attributes FifoQueue=true,MessageRetentionPeriod=1209600
  dlq_arn="arn:aws:sqs:us-east-1:000000000000:$dlq"
  awslocal sqs create-queue --queue-name "$name" \
    --attributes "{\"FifoQueue\":\"true\",\"VisibilityTimeout\":\"30\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"6\\\"}\"}"
}

create_queue mechanical-hub-billing-commands.fifo
create_queue mechanical-hub-orchestrator-events.fifo
