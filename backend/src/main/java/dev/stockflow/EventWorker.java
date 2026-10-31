package dev.stockflow;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class EventWorker {
  private final Support s;
  private final Dispatcher dispatcher;
  private final KafkaTemplate<String, String> kafka;
  private final TransactionTemplate tx;
  private final String mode;
  private final boolean enabled;

  public EventWorker(
      Support s,
      Dispatcher dispatcher,
      KafkaTemplate<String, String> kafka,
      PlatformTransactionManager manager,
      @Value("${app.messaging}") String mode,
      @Value("${app.jobs}") boolean enabled) {
    this.s = s;
    this.dispatcher = dispatcher;
    this.kafka = kafka;
    this.tx = new TransactionTemplate(manager);
    this.mode = mode;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 300)
  public void publish() {
    if (!enabled) return;
    for (int i = 0; i < 25; i++) {
      Boolean found =
          tx.execute(
              status -> {
                var rows =
                    s.db.queryForList(
                        "SELECT * FROM outbox_events WHERE published_at IS NULL AND NOT dead AND next_attempt<=now() ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (rows.isEmpty()) return false;
                var r = rows.getFirst();
                var e =
                    new Events.Event(
                        r.get("id").toString(),
                        r.get("aggregate_id").toString(),
                        r.get("target").toString(),
                        r.get("kind").toString(),
                        s.decode(r.get("payload").toString()));
                try {
                  if (mode.equals("kafka"))
                    kafka
                        .send("stockflow." + e.target(), e.aggregateId(), s.encode(e))
                        .get(12, TimeUnit.SECONDS);
                  else dispatcher.deliver(e);
                  s.db.update(
                      "UPDATE outbox_events SET published_at=now(),last_error=NULL WHERE id=?",
                      e.eventId());
                } catch (Exception ex) {
                  int attempt = ((Number) r.get("attempts")).intValue() + 1;
                  s.db.update(
                      "UPDATE outbox_events SET attempts=?,next_attempt=now()+(? * interval '1 second'),last_error=?,dead=? WHERE id=?",
                      attempt,
                      Math.min(60, attempt * 2),
                      ex.getClass().getSimpleName(),
                      attempt >= 10,
                      e.eventId());
                  LoggerFactory.getLogger(getClass())
                      .warn(
                          "Event {} {} delivery failed: {}",
                          e.eventId(),
                          e.kind(),
                          ex.getClass().getSimpleName());
                }
                return true;
              });
      if (!Boolean.TRUE.equals(found)) break;
    }
  }

  @KafkaListener(topics = "stockflow.${app.role}", autoStartup = "#{'${app.messaging}' == 'kafka'}")
  public void consume(String raw) throws Exception {
    dispatcher.deliver(s.json.readValue(raw, Events.Event.class));
  }
}
