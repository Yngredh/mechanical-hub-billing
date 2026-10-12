package com.fiap.mechanicalhub.billing.infrastructure.messaging

import com.fiap.mechanicalhub.billing.TestcontainersConfiguration
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.Message
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integração de ponta a ponta: PostgreSQL + LocalStack (SQS FIFO com DLQ) + WireMock no lugar do Mercado Pago.
 * Consumidor, relay e casos de uso reais; só o job de vencimento fica desligado (coberto em outro teste).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "billing.consumer.enabled=true",
        "billing.outbox.relay.enabled=true",
        "mercado-pago.access-token=tok",
    ],
)
class BillingFullStackIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var sqs: SqsTemplate

    @Autowired private lateinit var registry: MeterRegistry

    @Autowired private lateinit var sender: SqsOutboxMessageSender

    private val transactionId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()
    private val repairId = UUID.randomUUID()
    private val materialId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        wireMock.resetAll()
        wireMock.stubFor(
            post(urlEqualTo("/v1/orders")).willReturn(
                aResponse()
                    .withStatus(CREATED)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"id":"ORD-FS-$transactionId","status":"created","checkout_url":"https://mp/checkout"}"""),
            ),
        )
        jdbc.update("INSERT INTO service_prices (service_id, labor_price) VALUES (?, 100.00)", repairId)
        jdbc.update("INSERT INTO material_prices (material_id, unit_price) VALUES (?, 12.50)", materialId)
    }

    private fun sendCommand(body: String, dedup: String = UUID.randomUUID().toString()) =
        sqs.send { to ->
            to
                .queue(COMMANDS_QUEUE)
                .payload(body)
                .messageGroupId(orderId.toString())
                .messageDeduplicationId(dedup)
        }

    private fun receiveEvents(expected: Int): List<Message<*>> {
        val received = mutableListOf<Message<*>>()
        await.atMost(Duration.ofSeconds(WAIT_SECONDS)).untilAsserted {
            received +=
                sqs.receiveMany { from ->
                    from.queue(EVENTS_QUEUE).maxNumberOfMessages(MAX_BATCH).pollTimeout(Duration.ofSeconds(1))
                }
            assertTrue(received.size >= expected, "recebidas ${received.size} de $expected mensagens")
        }
        return received
    }

    private fun budgetCommand() =
        """
        {"eventId":"${UUID.randomUUID()}","transactionId":"$transactionId","orderId":"$orderId","type":"generateBudget",
         "version":1,"repairs":[{"repairId":"$repairId"}],
         "reservedMaterials":[{"materialId":"$materialId","quantity":3}]}
        """.trimIndent()

    private fun linkCommand(budgetId: UUID, key: String) =
        """
        {"transactionId":"$transactionId","orderId":"$orderId","type":"generatePaymentLink",
         "idempotencyKey":"$key","budgetId":"$budgetId","orderCost":137.50}
        """.trimIndent()

    private fun generateBudget(): UUID {
        sendCommand(budgetCommand())
        receiveEvents(1)
        return jdbc.queryForObject("SELECT budget_id FROM budgets WHERE transaction_id = ?", UUID::class.java, transactionId)!!
    }

    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Int::class.java, *args)

    @Test
    fun `given generateBudget command when consumed then budgetGenerated reaches the events queue`() {
        sendCommand(budgetCommand())

        val events = receiveEvents(1)

        assertTrue(
            events
                .single()
                .payload
                .toString()
                .contains("budgetGenerated"),
        )
        assertEquals(1, count("SELECT count(*) FROM budgets WHERE order_id = ?", orderId))
        await.atMost(Duration.ofSeconds(WAIT_SECONDS)).untilAsserted {
            assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'"))
        }
    }

    @Test
    fun `given same idempotency key and existing pending when linking then calls Mercado Pago once`() {
        val budgetId = generateBudget()
        val key = "$transactionId:GENERATE_PAYMENT_LINK"
        sendCommand(linkCommand(budgetId, key))
        receiveEvents(1)

        sendCommand(linkCommand(budgetId, key))
        sendCommand(linkCommand(budgetId, "$key:retry"))
        val replays = receiveEvents(2)

        assertTrue(replays.all { it.payload.toString().contains("https://mp/checkout") })
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/orders")).withHeader("X-Idempotency-Key", equalTo(key)))
        assertEquals(1, count("SELECT count(*) FROM payments WHERE transaction_id = ?", transactionId))
    }

    @Test
    fun `given Mercado Pago transient failure when linking then message is retried until it succeeds`() {
        val budgetId = generateBudget()
        wireMock.resetMappings()
        wireMock.stubFor(post(urlEqualTo("/v1/orders")).willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE)))
        sendCommand(linkCommand(budgetId, "$transactionId:GENERATE_PAYMENT_LINK"))
        await.atMost(Duration.ofSeconds(WAIT_SECONDS)).untilAsserted {
            assertTrue(wireMock.findAll(postRequestedFor(urlEqualTo("/v1/orders"))).isNotEmpty())
        }
        wireMock.stubFor(
            post(urlEqualTo("/v1/orders")).willReturn(
                aResponse().withStatus(CREATED).withBody("""{"id":"ORD-RETRY-$transactionId","checkout_url":"https://mp/retry"}"""),
            ),
        )

        val events = receiveEvents(1)

        assertTrue(
            events
                .single()
                .payload
                .toString()
                .contains("https://mp/retry"),
        )
    }

    @Test
    fun `given command with permanent error when consumed then goes to the DLQ`() {
        sendCommand("""{"type":"unknownCommand","orderId":"$orderId"}""")

        val dead = mutableListOf<Message<*>>()
        await.atMost(Duration.ofSeconds(WAIT_SECONDS)).untilAsserted {
            dead += sqs.receiveMany { from -> from.queue(COMMANDS_DLQ).pollTimeout(Duration.ofSeconds(1)) }
            assertEquals(1, dead.size)
        }
    }

    @Test
    fun `given active trace when sending to SQS then propagates traceparent header`() {
        val context =
            SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault(),
            )

        Span.wrap(context).makeCurrent().use {
            sender.send(orderId, UUID.randomUUID(), """{"type":"paymentConfirmed","orderId":"$orderId"}""")
        }

        val message = receiveEvents(1).single()
        assertEquals(
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            message.headers["traceparent"]?.toString(),
        )
    }

    @Test
    fun `given no active trace when sending to SQS then omits traceparent header`() {
        sender.send(orderId, UUID.randomUUID(), """{"type":"paymentConfirmed","orderId":"$orderId"}""")

        val message = receiveEvents(1).single()

        assertEquals(null, message.headers["traceparent"])
    }

    @Test
    fun `given flow executed when reading metrics then exposes outbox, payments and Mercado Pago series`() {
        val budgetId = generateBudget()
        sendCommand(linkCommand(budgetId, "$transactionId:GENERATE_PAYMENT_LINK"))
        receiveEvents(1)

        assertNotNull(registry.find("outbox_oldest_pending_age_seconds").gauge())
        assertTrue(
            registry
                .find("billing_payments")
                .tag("status", "PENDING")
                .gauge()!!
                .value() >= 1.0,
        )
        assertTrue(
            registry
                .find("mercadopago_request_seconds")
                .tag("operation", "createOrder")
                .timer()!!
                .count() >= 1,
        )
    }

    companion object {
        private const val COMMANDS_QUEUE = "mechanical-hub-billing-commands.fifo"
        private const val COMMANDS_DLQ = "mechanical-hub-billing-commands-dlq.fifo"
        private const val EVENTS_QUEUE = "mechanical-hub-orchestrator-events.fifo"
        private const val WAIT_SECONDS = 60L
        private const val MAX_BATCH = 10
        private const val CREATED = 201
        private const val SERVICE_UNAVAILABLE = 503

        private val localStack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4")).withServices("sqs")
        private val wireMock = WireMockServer(options().dynamicPort())

        init {
            localStack.start()
            wireMock.start()
            localStack.copyFileToContainer(MountableFile.forHostPath("localstack/init-queues.sh"), "/tmp/init-queues.sh")
            val result = localStack.execInContainer("sh", "/tmp/init-queues.sh")
            check(result.exitCode == 0) { "Falha ao criar filas: ${result.stderr}" }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.sqs.endpoint") { localStack.endpoint.toString() }
            registry.add("spring.cloud.aws.region.static") { localStack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localStack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localStack.secretKey }
            registry.add("mercado-pago.base-url") { "http://localhost:${wireMock.port()}" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            wireMock.stop()
            localStack.stop()
        }
    }
}
