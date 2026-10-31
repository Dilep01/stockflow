package dev.stockflow;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Dispatcher {
  private final Support s;
  private final List<Events.Handler> handlers;

  public Dispatcher(Support s, List<Events.Handler> handlers) {
    this.s = s;
    this.handlers = handlers;
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void deliver(Events.Event e) {
    if (e.schemaVersion() != 1)
      throw new IllegalArgumentException("Unsupported event schema version");
    var handler = handlers.stream().filter(h -> h.target().equals(e.target())).findFirst();
    if (handler.isEmpty())
      throw new IllegalArgumentException("Event target does not belong to this service");
    if (s.db.update(
            "INSERT INTO processed_messages(event_id) VALUES (?) ON CONFLICT DO NOTHING",
            e.eventId())
        == 0) return;
    try (var correlation = org.slf4j.MDC.putCloseable("correlationId", e.aggregateId())) {
      handler.get().handle(e);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info("Applied event {} type={} target={}", e.eventId(), e.kind(), e.target());
    }
  }
}
