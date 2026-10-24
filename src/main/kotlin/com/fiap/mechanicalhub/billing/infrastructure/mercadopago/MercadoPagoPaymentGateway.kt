package com.fiap.mechanicalhub.billing.infrastructure.mercadopago

import com.fiap.mechanicalhub.billing.application.payment.CreateOrderCommand
import com.fiap.mechanicalhub.billing.application.payment.CreatedOrder
import com.fiap.mechanicalhub.billing.application.payment.PaymentGateway
import com.fiap.mechanicalhub.billing.application.payment.PermanentGatewayException
import com.fiap.mechanicalhub.billing.application.payment.TransientGatewayException
import com.fiap.mechanicalhub.billing.domain.payment.CancelOutcome
import com.fiap.mechanicalhub.billing.domain.payment.OrderLookup
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Adapter HTTP da Orders API do Mercado Pago. Sem retry: o retry é do consumidor SQS. */
@Component
class MercadoPagoPaymentGateway(
    private val properties: MercadoPagoProperties,
    private val registry: MeterRegistry = Metrics.globalRegistry,
) : PaymentGateway {
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override fun createOrder(command: CreateOrderCommand): CreatedOrder {
        val body = MercadoPagoJson.createOrderBody(command)
        val response = send("createOrder", post("/v1/orders", command.idempotencyKey, body))
        return when (classify(response.statusCode())) {
            HttpClass.SUCCESS -> MercadoPagoJson.parseCreated(response.body())
            HttpClass.TRANSIENT -> throw TransientGatewayException("MP indisponivel (${response.statusCode()})")
            HttpClass.PERMANENT -> {
                log.error("MP rejeitou a criacao ({}): {}", response.statusCode(), response.body().take(MAX_LOGGED_BODY))
                throw PermanentGatewayException("MP rejeitou a criacao (${response.statusCode()})")
            }
        }
    }

    override fun getOrder(mpOrderId: String): OrderLookup {
        val response =
            runCatching { send("getOrder", base("/v1/orders/$mpOrderId").GET().build()) }
                .getOrElse { return OrderLookup.Transient }
        return when (classify(response.statusCode())) {
            HttpClass.SUCCESS -> MercadoPagoJson.parseLookup(response.body())
            HttpClass.TRANSIENT -> OrderLookup.Transient
            HttpClass.PERMANENT -> OrderLookup.Permanent
        }
    }

    override fun cancelOrder(mpOrderId: String, idempotencyKey: String): CancelOutcome {
        val response =
            runCatching { send("cancelOrder", post("/v1/orders/$mpOrderId/cancel", idempotencyKey, "")) }
                .getOrElse { return CancelOutcome.Transient }
        if (response.statusCode() == STATUS_CONFLICT) return CancelOutcome.Conflict
        return when (classify(response.statusCode())) {
            HttpClass.SUCCESS -> CancelOutcome.Canceled
            HttpClass.TRANSIENT -> CancelOutcome.Transient
            HttpClass.PERMANENT -> CancelOutcome.Permanent
        }
    }

    private fun base(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create(properties.baseUrl.trimEnd('/') + path))
            .timeout(READ_TIMEOUT)
            .header("Authorization", "Bearer ${properties.accessToken}")
            .header("Accept", "application/json")

    private fun post(path: String, idempotencyKey: String, body: String): HttpRequest =
        base(path)
            .header("Content-Type", "application/json")
            .header("X-Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    /** Mede latência por operação (`mercadopago_request_seconds{operation,status}`) e alerta em 401/404. */
    private fun send(operation: String, request: HttpRequest): HttpResponse<String> {
        val sample = Timer.start(registry)
        var status = "error"
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            status = response.statusCode().toString()
            if (response.statusCode() == STATUS_UNAUTHORIZED || response.statusCode() == STATUS_NOT_FOUND) {
                registry.counter("billing_alerts_total", "type", "MP_$status").increment()
                log.error("ALERTA: Mercado Pago respondeu {} em {}", status, operation)
            }
            return response
        } catch (exception: IOException) {
            throw TransientGatewayException("Falha de comunicacao com o Mercado Pago", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransientGatewayException("Chamada ao Mercado Pago interrompida", exception)
        } finally {
            sample.stop(
                Timer
                    .builder("mercadopago_request_seconds")
                    .tag("operation", operation)
                    .tag("status", status)
                    .register(registry),
            )
        }
    }

    private fun classify(status: Int): HttpClass =
        when {
            status in HTTP_SUCCESS_RANGE -> HttpClass.SUCCESS
            status == STATUS_TOO_MANY_REQUESTS || status >= STATUS_SERVER_ERROR -> HttpClass.TRANSIENT
            else -> HttpClass.PERMANENT
        }

    private enum class HttpClass { SUCCESS, TRANSIENT, PERMANENT }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
        val HTTP_SUCCESS_RANGE = 200..299
        val log = LoggerFactory.getLogger(MercadoPagoPaymentGateway::class.java)
        const val MAX_LOGGED_BODY = 1000
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_NOT_FOUND = 404
        const val STATUS_CONFLICT = 409
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val STATUS_SERVER_ERROR = 500
    }
}
