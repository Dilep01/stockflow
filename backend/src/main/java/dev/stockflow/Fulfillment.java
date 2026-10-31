package dev.stockflow;

import static dev.stockflow.Support.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fulfillment")
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'fulfillment'")
public class Fulfillment implements Events.Handler {
  private final Support s;
  private final Events events;

  public Fulfillment(Support s, Events events) {
    this.s = s;
    this.events = events;
  }

  public String target() {
    return "fulfillment";
  }

  @GetMapping("/shipments")
  public Object list() {
    String sql =
        "SELECT sh.*,f.customer_id FROM shipments sh JOIN fulfillment_orders f USING(order_id)";
    List<Map<String, Object>> rows =
        role().equals("ADMIN")
            ? s.db.queryForList(sql + " ORDER BY sh.created_at DESC LIMIT 200")
            : role().equals("OPERATOR")
                ? s.db.queryForList(
                    sql + " WHERE warehouse_id=? ORDER BY sh.created_at DESC LIMIT 200",
                    user().getClaimAsString("warehouse"))
                : s.db.queryForList(
                    sql + " WHERE customer_id=? ORDER BY sh.created_at DESC LIMIT 200", uid());
    for (var row : rows) {
      row.put(
          "items",
          s.db.queryForList("SELECT * FROM shipment_items WHERE shipment_id=?", row.get("id")));
      row.put(
          "timeline",
          s.db.queryForList(
              "SELECT state,created_at FROM shipment_events WHERE shipment_id=? ORDER BY id",
              row.get("id")));
    }
    return rows;
  }

  @SuppressWarnings("unchecked")
  public void handle(Events.Event e) {
    String order = e.aggregateId();
    switch (e.kind()) {
      case "CreateFulfillment" -> {
        s.db.update(
            "INSERT INTO fulfillment_orders(order_id,customer_id,state,items) VALUES (?,?,'READY',?) ON CONFLICT DO NOTHING",
            order,
            e.payload().get("customerId"),
            s.encode(e.payload().get("items")));
        var f = s.one("SELECT * FROM fulfillment_orders WHERE order_id=? FOR UPDATE", order);
        if (f.get("state").equals("CANCELLED")) return;
        for (var a : (List<Map<String, Object>>) e.payload().get("allocations")) {
          String wh = str(a, "warehouseId");
          var found =
              s.db.queryForList(
                  "SELECT id FROM shipments WHERE order_id=? AND warehouse_id=?", order, wh);
          String shipment = found.isEmpty() ? id() : str(found.getFirst(), "id");
          if (found.isEmpty()) {
            s.db.update(
                "INSERT INTO shipments(id,order_id,warehouse_id,tracking) VALUES (?,?,?,?)",
                shipment,
                order,
                wh,
                "DEMO-" + shipment.substring(0, 8).toUpperCase());
            timeline(shipment, "READY");
          }
          s.db.update(
              "INSERT INTO shipment_items(shipment_id,sku_id,quantity) VALUES (?,?,?) ON CONFLICT DO NOTHING",
              shipment,
              a.get("skuId"),
              a.get("quantity"));
        }
        events.emit(order, "orders", "FulfillmentReady", Map.of());
      }
      case "CancelFulfillment" -> {
        s.db.update(
            "INSERT INTO fulfillment_orders(order_id,customer_id,state) VALUES (?,?,'CANCELLED') ON CONFLICT DO NOTHING",
            order,
            e.payload().get("customerId"));
        var f = s.one("SELECT * FROM fulfillment_orders WHERE order_id=? FOR UPDATE", order);
        var shipments =
            s.db.queryForList(
                "SELECT * FROM shipments WHERE order_id=? ORDER BY id FOR UPDATE", order);
        if (shipments.stream()
            .anyMatch(
                sh ->
                    List.of("DISPATCH_PENDING", "DISPATCHED", "IN_TRANSIT", "DELIVERED")
                        .contains(str(sh, "state")))) {
          events.emit(order, "orders", "CancellationRejected", Map.of("state", progress(order)));
          return;
        }
        s.db.update("UPDATE fulfillment_orders SET state='CANCELLED' WHERE order_id=?", order);
        s.db.update("UPDATE shipments SET state='CANCELLED' WHERE order_id=?", order);
        for (var sh : shipments) timeline(str(sh, "id"), "CANCELLED");
        events.emit(order, "orders", "FulfillmentCancelled", Map.of());
      }
      case "StockConsumed" -> {
        String shipment = str(e.payload(), "shipmentId");
        s.one("SELECT * FROM fulfillment_orders WHERE order_id=? FOR UPDATE", order);
        if (s.db.update(
                "UPDATE shipments SET state='DISPATCHED' WHERE id=? AND state='DISPATCH_PENDING'",
                shipment)
            == 1) timeline(shipment, "DISPATCHED");
        publishProgress(order);
      }
      case "ReturnRestocked" ->
          s.db.update(
              "UPDATE return_requests SET state='CLOSED' WHERE id=? AND state='RESTOCK_PENDING'",
              e.payload().get("returnId"));
      default -> throw new IllegalArgumentException("Unknown fulfillment event " + e.kind());
    }
  }

  public record ShipmentAction(@NotBlank String action) {}

  @PostMapping("/shipments/{id}/actions")
  @Transactional
  public Object action(@PathVariable String id, @Valid @RequestBody ShipmentAction r) {
    var original = s.one("SELECT * FROM shipments WHERE id=?", id);
    warehouse(str(original, "warehouse_id"));
    String order = str(original, "order_id");
    s.one("SELECT * FROM fulfillment_orders WHERE order_id=? FOR UPDATE", order);
    var sh = s.one("SELECT * FROM shipments WHERE id=? FOR UPDATE", id);
    String state = str(sh, "state");
    String next =
        switch (r.action()) {
          case "pick" -> "PICKED";
          case "pack" -> "PACKED";
          case "dispatch" -> "DISPATCH_PENDING";
          case "transit" -> "IN_TRANSIT";
          case "deliver" -> "DELIVERED";
          default -> throw new IllegalArgumentException();
        };
    if (state.equals(next)
        || (r.action().equals("dispatch")
            && List.of("DISPATCHED", "IN_TRANSIT", "DELIVERED").contains(state))) return sh;
    String expected =
        switch (r.action()) {
          case "pick" -> "READY";
          case "pack" -> "PICKED";
          case "dispatch" -> "PACKED";
          case "transit" -> "DISPATCHED";
          case "deliver" -> "IN_TRANSIT";
          default -> "";
        };
    require(state.equals(expected), "Invalid shipment transition");
    s.db.update("UPDATE shipments SET state=? WHERE id=?", next, id);
    timeline(id, next);
    if (r.action().equals("dispatch"))
      events.emit(
          order,
          "inventory",
          "Consume",
          Map.of("warehouseId", sh.get("warehouse_id"), "shipmentId", id));
    else publishProgress(order);
    return Map.of("state", next);
  }

  void timeline(String shipment, String state) {
    s.db.update("INSERT INTO shipment_events(shipment_id,state) VALUES (?,?)", shipment, state);
  }

  String progress(String order) {
    var states =
        s.db.queryForList("SELECT state FROM shipments WHERE order_id=?", String.class, order);
    if (!states.isEmpty() && states.stream().allMatch(x -> x.equals("DELIVERED")))
      return "DELIVERED";
    long sent =
        states.stream()
            .filter(x -> List.of("DISPATCHED", "IN_TRANSIT", "DELIVERED").contains(x))
            .count();
    if (sent == states.size() && sent > 0) return "SHIPPED";
    return sent > 0 ? "PARTIALLY_SHIPPED" : "FULFILLING";
  }

  void publishProgress(String order) {
    events.emit(order, "orders", "ShipmentProgress", Map.of("state", progress(order)));
  }

  @GetMapping("/returns")
  public Object returns() {
    String sql =
        "SELECT r.*,sh.warehouse_id,sh.order_id FROM return_requests r JOIN shipments sh ON sh.id=r.shipment_id";
    return role().equals("ADMIN")
        ? s.db.queryForList(sql + " ORDER BY r.created_at DESC LIMIT 200")
        : role().equals("OPERATOR")
            ? s.db.queryForList(
                sql + " WHERE sh.warehouse_id=? ORDER BY r.created_at DESC LIMIT 200",
                user().getClaimAsString("warehouse"))
            : s.db.queryForList(
                sql + " WHERE r.customer_id=? ORDER BY r.created_at DESC LIMIT 200", uid());
  }

  public record ReturnRequest(
      @NotBlank String shipmentId,
      @NotBlank String skuId,
      @Min(1) int quantity,
      @NotBlank @Size(max = 300) String reason,
      @NotBlank @Size(max = 36) String requestId) {}

  @PostMapping("/returns")
  @Transactional
  public Object requestReturn(@Valid @RequestBody ReturnRequest r) {
    roles("CUSTOMER", "ADMIN");
    var sh =
        s.one(
            "SELECT sh.*,f.customer_id,f.items FROM shipments sh JOIN fulfillment_orders f USING(order_id) WHERE sh.id=? FOR UPDATE OF sh",
            r.shipmentId());
    owner(str(sh, "customer_id"));
    require(sh.get("state").equals("DELIVERED"), "Return requires delivery");
    var existing = s.db.queryForList("SELECT * FROM return_requests WHERE id=?", r.requestId());
    if (!existing.isEmpty()) {
      var old = existing.getFirst();
      require(
          str(old, "shipment_id").equals(r.shipmentId())
              && str(old, "sku_id").equals(r.skuId())
              && number(old, "quantity") == r.quantity()
              && str(old, "reason").equals(r.reason()),
          "Return request ID reused");
      return old;
    }
    int delivered =
        number(
            s.one(
                "SELECT quantity FROM shipment_items WHERE shipment_id=? AND sku_id=?",
                r.shipmentId(),
                r.skuId()),
            "quantity");
    Integer used =
        s.db.queryForObject(
            "SELECT coalesce(sum(quantity),0) FROM return_requests WHERE shipment_id=? AND sku_id=? AND state<>'REJECTED'",
            Integer.class,
            r.shipmentId(),
            r.skuId());
    require(used + r.quantity() <= delivered, "Return exceeds delivered quantity");
    s.db.update(
        "INSERT INTO return_requests(id,shipment_id,sku_id,customer_id,quantity,reason) VALUES (?,?,?,?,?,?)",
        r.requestId(),
        r.shipmentId(),
        r.skuId(),
        sh.get("customer_id"),
        r.quantity(),
        r.reason());
    return Map.of("id", r.requestId(), "state", "REQUESTED");
  }

  public record ReturnAction(@NotBlank String action, String disposition) {}

  @PostMapping("/returns/{id}/actions")
  @Transactional
  @SuppressWarnings("unchecked")
  public Object returnAction(@PathVariable String id, @Valid @RequestBody ReturnAction action) {
    var r =
        s.one(
            "SELECT r.*,sh.warehouse_id,sh.order_id,f.items FROM return_requests r JOIN shipments sh ON sh.id=r.shipment_id JOIN fulfillment_orders f USING(order_id) WHERE r.id=? FOR UPDATE OF r",
            id);
    warehouse(str(r, "warehouse_id"));
    String state = str(r, "state");
    if (action.action().equals("approve") && state.equals("APPROVED")
        || action.action().equals("receive") && state.equals("RECEIVED")
        || action.action().equals("inspect")
            && List.of("RESTOCK_PENDING", "CLOSED").contains(state)) return r;
    String next =
        switch (action.action()) {
          case "approve" -> {
            require(state.equals("REQUESTED"), "Return is not requested");
            yield "APPROVED";
          }
          case "reject" -> {
            require(state.equals("REQUESTED"), "Return is not requested");
            yield "REJECTED";
          }
          case "receive" -> {
            require(state.equals("APPROVED"), "Return is not approved");
            yield "RECEIVED";
          }
          case "inspect" -> {
            require(state.equals("RECEIVED"), "Return has not been received");
            require(
                List.of("SELLABLE", "DAMAGED").contains(action.disposition()),
                "Choose disposition");
            yield "RESTOCK_PENDING";
          }
          default -> throw new IllegalArgumentException();
        };
    if (next.equals("RESTOCK_PENDING")) {
      BigDecimal price = BigDecimal.ZERO;
      try {
        List<Map<String, Object>> items = s.json.readValue(str(r, "items"), List.class);
        for (var item : items)
          if (str(item, "skuId").equals(str(r, "sku_id")))
            price = new BigDecimal(str(item, "price"));
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
      s.db.update(
          "UPDATE return_requests SET disposition=?,refund=? WHERE id=?",
          action.disposition(),
          price.multiply(BigDecimal.valueOf(number(r, "quantity"))),
          id);
      events.emit(
          str(r, "order_id"),
          "inventory",
          "Restock",
          Map.of(
              "returnId",
              id,
              "warehouseId",
              r.get("warehouse_id"),
              "skuId",
              r.get("sku_id"),
              "quantity",
              r.get("quantity"),
              "disposition",
              action.disposition()));
    }
    s.db.update("UPDATE return_requests SET state=? WHERE id=?", next, id);
    return Map.of("state", next);
  }
}
