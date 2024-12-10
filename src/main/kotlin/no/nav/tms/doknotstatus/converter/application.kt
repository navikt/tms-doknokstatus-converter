package no.nav.tms.doknotstatus.converter

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import no.nav.doknotifikasjon.schemas.DoknotifikasjonStatus
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger(Application::class.java)

fun main() {
    val environment = Environment()
    val doknotConsumer = KafkaConsumer<String, DoknotifikasjonStatus>(environment.consumerProps())
    val brukerVarselProducer = KafkaProducer<String, String>(environment.producerProps())

    val doknotifikasjonStatusConverter = DoknotifikasjonStatusConverter(
        consumer = doknotConsumer,
        producer = brukerVarselProducer,
        brukervarselTopic = environment.brukervarselTopic,
        doknotifikasjonStatusTopic = environment.doknotifikasjonStatusTopicName
    )

    embeddedServer(
        factory = Netty,
        configure = {
            connector {
                port = 8080
            }
        },
        module = {
            routing {
                get("/isAlive") {
                    if(doknotifikasjonStatusConverter.isAlive()) {
                        call.respondText(text = "ALIVE", contentType = ContentType.Text.Plain)
                    } else {
                        call.respondText(text = "NOTALIVE", contentType = ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    }
                }

                get("/isReady") {
                    call.respondText(text = "READY", contentType = ContentType.Text.Plain)
                }
            }

            this.monitor.subscribe(ApplicationStarted) {
                doknotifikasjonStatusConverter.startPolling()
            }

            this.monitor.subscribe(ApplicationStopPreparing) {
                brukerVarselProducer.shutdown()

                runBlocking {
                    doknotifikasjonStatusConverter.stopPolling()
                }

            }
        }
    ).start(wait = true)

    embeddedServer(Netty, port = 8080) {

    }.start(wait = true)
}

private fun KafkaProducer<String, String>.shutdown() {
    try {
        flush()
        close()
        logger.info("Produsent for kafka-eventer er flushet og lukket.")
    } catch (e: Exception) {
        logger.warn("Klarte ikke å flushe og lukke produsent. Det kan være eventer som ikke ble produsert.")
    }
}
