package dev.stockflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'notification'")
public class Notifications implements Events.Handler {
  private final Support s;
  private final JavaMailSender mail;
  private final boolean jobs;

  public Notifications(Support s, JavaMailSender mail, @Value("${app.jobs}") boolean jobs) {
    this.s = s;
    this.mail = mail;
    this.jobs = jobs;
  }

  public String target() {
    return "notification";
  }

  public void handle(Events.Event e) {
    s.db.update(
        "INSERT INTO notifications(event_id,recipient,subject,body) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
        e.eventId(),
        e.payload().get("email"),
        "Your Stockflow order is confirmed",
        "Demo order "
            + e.aggregateId()
            + " is confirmed. This is a local simulation; no payment was taken.");
  }

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void send() {
    if (!jobs) return;
    for (var n :
        s.db.queryForList(
            "SELECT * FROM notifications WHERE state='PENDING' ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED")) {
      try {
        var m = new SimpleMailMessage();
        m.setFrom("orders@stockflow.local");
        m.setTo(n.get("recipient").toString());
        m.setSubject(n.get("subject").toString());
        m.setText(n.get("body").toString());
        mail.send(m);
        s.db.update("UPDATE notifications SET state='SENT' WHERE event_id=?", n.get("event_id"));
      } catch (Exception ex) {
        break;
      }
    }
  }
}
