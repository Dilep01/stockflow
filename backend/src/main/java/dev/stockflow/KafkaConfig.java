package dev.stockflow;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "app.messaging", havingValue = "kafka")
public class KafkaConfig {
  @Bean
  KafkaAdmin.NewTopics topics() {
    return new KafkaAdmin.NewTopics(
        java.util.stream.Stream.of(
                "identity", "catalog", "inventory", "orders", "fulfillment", "notification")
            .flatMap(n -> java.util.stream.Stream.of(n, n + ".DLT"))
            .map(n -> TopicBuilder.name("stockflow." + n).partitions(3).replicas(1).build())
            .toArray(org.apache.kafka.clients.admin.NewTopic[]::new));
  }

  @Bean
  DefaultErrorHandler kafkaErrors(KafkaTemplate<String, String> template) {
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 4));
  }
}
