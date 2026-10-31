package dev.stockflow;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Events {
  public record Event(
      String eventId,
      String aggregateId,
      String target,
      String kind,
      Map<String, Object> payload,
      int schemaVersion,
      String occurredAt) {
    public Event(
        String eventId,
        String aggregateId,
        String target,
        String kind,
        Map<String, Object> payload) {
      this(eventId, aggregateId, target, kind, payload, 1, java.time.Instant.now().toString());
    }
  }

  public interface Handler {
    String target();

    void handle(Event e);
  }

  private final Support s;

  public Events(Support s) {
    this.s = s;
  }

  public void emit(String aggregate, String target, String kind, Map<String, Object> payload) {
    s.db.update(
        "INSERT INTO outbox_events(id,aggregate_id,target,kind,payload) VALUES (?,?,?,?,?)",
        Support.id(),
        aggregate,
        target,
        kind,
        s.encode(payload));
  }
}
