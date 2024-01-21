package adapters.kafka

//import akka.actor.ActorSystem
import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.scaladsl._
import deliverydate.ExternalEvent
import io.circe.generic.auto._
import io.circe.parser._
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.serialization._
import org.slf4j.LoggerFactory
import service.DeliveryDateService

import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DeliveryDateServiceKafkaAdapter {
  private val log = LoggerFactory.getLogger(this.getClass)

  // Can we inject this in?
//  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "kafka-consumer-system")
//  implicit val ec: ExecutionContextExecutor = system.executionContext

  private val groupId = "delivery-date-group" // TODO what does NGTT use?
  val topic = "external-events"

  def consumeFromKafka(deliveryDateService: DeliveryDateService, actorSystem: ActorSystem[_]): Unit = {
    implicit val system: ActorSystem[Nothing] = actorSystem
    implicit val ec: ExecutionContextExecutor = system.executionContext


    val consumerSettings: ConsumerSettings[String, String] = ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers("localhost:9092") // TODO get this up and running
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") // // TODO what?
      .withStopTimeout(0.seconds) // TODO Why?

    val kafkaSource: Source[ConsumerRecord[String, String], Consumer.Control] = Consumer.plainSource(consumerSettings, Subscriptions.topics(topic))

    kafkaSource
      .map(consumerRecord => consumerRecord.value())
      .map { x =>
        log.info(s"***** DWL ${x}") // TODO Fix serialization issues
        decode[ExternalEvent](x)
      }
      .flatMapConcat {
        case Left(error) =>
          log.error(s"Failed to parse external event due to: $error")
          Source.empty
        case Right(event) => Source.single(event)
      }
      .log("external-event-stream").addAttributes(Attributes.logLevels(onElement = LogLevels.Info))
      .mapAsync(4) { event =>
        deliveryDateService
          .upsertDeliveryDate(UUID.fromString(event.packageId), Instant.parse(event.deliveryDate))
          .recover {
            case ex: Throwable => s"DeliveryDateService failed to process event due to ${ex.getMessage}"
          }
      }
      .runWith(Sink.ignore)
      .onComplete {
        case Success(_) =>
          log.info("Consumer successfully closed.")
        case Failure(exception) =>
          log.error(s"Failed to create Kafka Consumer stream: $exception")
          system.terminate()
      }
  }
}

