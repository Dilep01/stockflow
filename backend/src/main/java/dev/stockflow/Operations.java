package dev.stockflow;

import static dev.stockflow.Support.*;

import io.micrometer.core.instrument.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ops/{service}")
public class Operations {
  private final Support s;
  private final String role, mode;

  public Operations(
      Support s,
      @Value("${app.role}") String role,
      @Value("${app.messaging}") String mode,
      MeterRegistry registry) {
    this.s = s;
    this.role = role;
    this.mode = mode;
    Gauge.builder(
            "stockflow.outbox.pending",
            s,
            x ->
                x.db.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Double.class))
        .register(registry);
    Gauge.builder(
            "stockflow.outbox.dead",
            s,
            x -> x.db.queryForObject("SELECT count(*) FROM outbox_events WHERE dead", Double.class))
        .register(registry);
  }

  @GetMapping("/status")
  public Object status() {
    roles("ADMIN");
    return Map.of(
        "role",
        role,
        "messaging",
        mode,
        "pending",
        s.db.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND NOT dead",
            Integer.class),
        "dead",
        s.db.queryForObject("SELECT count(*) FROM outbox_events WHERE dead", Integer.class),
        "processed",
        s.db.queryForObject("SELECT count(*) FROM processed_messages", Integer.class),
        "events",
        s.db.queryForList(
            "SELECT id,aggregate_id,target,kind,created_at,published_at,attempts,last_error,dead FROM outbox_events ORDER BY created_at DESC LIMIT 50"));
  }

  @PostMapping("/events/{id}/retry")
  @Transactional
  public Object retry(@PathVariable String id) {
    roles("ADMIN");
    require(
        s.db.update(
                "UPDATE outbox_events SET dead=false,attempts=0,next_attempt=now(),last_error=NULL WHERE id=? AND published_at IS NULL",
                id)
            == 1,
        "Only unpublished events can be retried");
    return Map.of("status", "queued");
  }
}
