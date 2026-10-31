package dev.stockflow;

import static dev.stockflow.Support.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'inventory'")
public class Inventory implements Events.Handler {
  private final Support s;
  private final Events events;
  private final int expiry;
  private final boolean jobs;
  private final ProductLookup products;

  public Inventory(
      Support s,
      Events events,
      @Value("${app.reservation-seconds}") int expiry,
      @Value("${app.jobs}") boolean jobs,
      ProductLookup products) {
    this.s = s;
    this.events = events;
    this.expiry = expiry;
    this.jobs = jobs;
    this.products = products;
  }

  public String target() {
    return "inventory";
  }

  @GetMapping("/warehouses")
  public Object warehouses() {
    return s.db.queryForList("SELECT * FROM warehouses ORDER BY priority,id");
  }

  public record Warehouse(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 100) String city,
      @Min(0) int priority) {}

  @PostMapping("/warehouses")
  public Object warehouseCreate(@Valid @RequestBody Warehouse r) {
    roles("ADMIN");
    String id = id();
    s.db.update(
        "INSERT INTO warehouses(id,name,city,priority) VALUES (?,?,?,?)",
        id,
        r.name(),
        r.city(),
        r.priority());
    return Map.of("id", id);
  }

  @GetMapping("/balances")
  public Object balances() {
    if (role().equals("OPERATOR"))
      return s.db.queryForList(
          "SELECT b.*,w.name warehouse_name,w.city,b.on_hand-b.reserved available FROM stock_balances b JOIN warehouses w ON w.id=b.warehouse_id WHERE b.warehouse_id=? ORDER BY w.priority,b.sku_id",
          user().getClaimAsString("warehouse"));
    return s.db.queryForList(
        "SELECT b.*,w.name warehouse_name,w.city,b.on_hand-b.reserved available FROM stock_balances b JOIN warehouses w ON w.id=b.warehouse_id ORDER BY w.priority,b.sku_id");
  }

  @GetMapping("/movements")
  public Object movements() {
    roles("ADMIN", "OPERATOR");
    return role().equals("ADMIN")
        ? s.db.queryForList("SELECT * FROM stock_movements ORDER BY created_at DESC LIMIT 200")
        : s.db.queryForList(
            "SELECT * FROM stock_movements WHERE warehouse_id=? ORDER BY created_at DESC LIMIT 200",
            user().getClaimAsString("warehouse"));
  }

  @GetMapping("/reservations")
  public Object reservations() {
    roles("ADMIN");
    return s.db.queryForList("SELECT * FROM reservations ORDER BY updated_at DESC LIMIT 200");
  }

  public record Adjustment(
      @NotBlank String warehouseId,
      @NotBlank String skuId,
      @Min(-100000) @Max(100000) int quantity,
      @NotBlank @Size(max = 300) String reason,
      @NotBlank @Size(max = 100) String operationId) {}

  @PostMapping("/adjustments")
  @Transactional
  public Object adjust(@Valid @RequestBody Adjustment r) {
    warehouse(r.warehouseId());
    products.get(r.skuId());
    require(r.quantity() != 0, "Quantity must be non-zero");
    if (!operation(r.operationId(), s.encode(r))) return Map.of("status", "already applied");
    ensureBalance(r.warehouseId(), r.skuId());
    require(
        s.db.update(
                "UPDATE stock_balances SET on_hand=on_hand+?,version=version+1 WHERE warehouse_id=? AND sku_id=? AND on_hand+?>=reserved",
                r.quantity(),
                r.warehouseId(),
                r.skuId(),
                r.quantity())
            == 1,
        "Adjustment would consume reserved stock");
    movement(
        r.warehouseId(),
        r.skuId(),
        r.quantity(),
        0,
        0,
        "ADJUSTMENT",
        r.operationId(),
        r.reason(),
        uid());
    return Map.of("status", "applied");
  }

  boolean operation(String id, String request) {
    if (s.db.update(
            "INSERT INTO inventory_operations(id,request) VALUES (?,?) ON CONFLICT DO NOTHING",
            id,
            request)
        == 1) return true;
    require(
        s.one("SELECT request FROM inventory_operations WHERE id=?", id)
            .get("request")
            .equals(request),
        "Operation ID reused with different request");
    return false;
  }

  void ensureBalance(String warehouse, String sku) {
    s.db.update(
        "INSERT INTO stock_balances(warehouse_id,sku_id) VALUES (?,?) ON CONFLICT DO NOTHING",
        warehouse,
        sku);
  }

  void movement(
      String wh,
      String sku,
      int hand,
      int held,
      int damaged,
      String kind,
      String reference,
      String reason,
      String actor) {
    s.db.update(
        "INSERT INTO stock_movements(id,warehouse_id,sku_id,on_hand_delta,reserved_delta,damaged_delta,kind,reference,reason,actor) VALUES (?,?,?,?,?,?,?,?,?,?)",
        id(),
        wh,
        sku,
        hand,
        held,
        damaged,
        kind,
        reference,
        reason,
        actor);
  }

  @SuppressWarnings("unchecked")
  public void handle(Events.Event e) {
    switch (e.kind()) {
      case "Reserve" ->
          reserve(e.aggregateId(), (List<Map<String, Object>>) e.payload().get("items"));
      case "Commit" -> commit(e.aggregateId());
      case "Release" -> release(e.aggregateId(), "RELEASED");
      case "Consume" ->
          consume(e.aggregateId(), str(e.payload(), "warehouseId"), str(e.payload(), "shipmentId"));
      case "Restock" -> restock(e);
      default -> throw new IllegalArgumentException("Unknown inventory event " + e.kind());
    }
  }

  void reserve(String order, List<Map<String, Object>> items) {
    s.db.update(
        "INSERT INTO reservations(order_id,state,expires_at) VALUES (?,'PENDING',now()+(? * interval '1 second')) ON CONFLICT DO NOTHING",
        order,
        expiry);
    var reservation = s.one("SELECT * FROM reservations WHERE order_id=? FOR UPDATE", order);
    if (!reservation.get("state").equals("PENDING")) return;
    // Lock only requested SKUs, in a stable order, before calculating allocations.
    var skuIds = items.stream().map(i -> str(i, "skuId")).distinct().sorted().toList();
    require(!skuIds.isEmpty() && skuIds.size() == items.size(), "Unique product lines required");
    String placeholders = String.join(",", Collections.nCopies(skuIds.size(), "?"));
    var balances =
        s.db.queryForList(
            "SELECT b.*,w.priority FROM stock_balances b JOIN warehouses w ON b.warehouse_id=w.id WHERE b.sku_id IN ("
                + placeholders
                + ") ORDER BY b.warehouse_id,b.sku_id FOR UPDATE OF b",
            skuIds.toArray());
    var warehouses = s.db.queryForList("SELECT * FROM warehouses ORDER BY priority,id");
    String single = null;
    for (var w : warehouses) {
      String wh = str(w, "id");
      boolean fits =
          items.stream()
              .allMatch(
                  item ->
                      balances.stream()
                          .anyMatch(
                              b ->
                                  str(b, "warehouse_id").equals(wh)
                                      && str(b, "sku_id").equals(str(item, "skuId"))
                                      && number(b, "on_hand") - number(b, "reserved")
                                          >= number(item, "quantity")));
      if (fits) {
        single = wh;
        break;
      }
    }
    List<Map<String, Object>> allocations = new ArrayList<>();
    for (var item : items) {
      int remaining = number(item, "quantity");
      require(remaining > 0, "Positive quantity required");
      String sku = str(item, "skuId");
      for (var w : warehouses) {
        String wh = str(w, "id");
        if (single != null && !single.equals(wh)) continue;
        for (var b : balances) {
          if (!str(b, "warehouse_id").equals(wh) || !str(b, "sku_id").equals(sku)) continue;
          int take = Math.min(remaining, number(b, "on_hand") - number(b, "reserved"));
          if (take > 0) {
            allocations.add(Map.of("warehouseId", wh, "skuId", sku, "quantity", take));
            remaining -= take;
          }
        }
        if (remaining == 0) break;
      }
      if (remaining > 0) {
        s.db.update(
            "UPDATE reservations SET state='REJECTED',updated_at=now() WHERE order_id=?", order);
        events.emit(
            order, "orders", "StockRejected", Map.of("reason", "Insufficient stock for " + sku));
        return;
      }
    }
    for (var a : allocations) {
      String wh = str(a, "warehouseId"), sku = str(a, "skuId");
      int q = number(a, "quantity");
      require(
          s.db.update(
                  "UPDATE stock_balances SET reserved=reserved+?,version=version+1 WHERE warehouse_id=? AND sku_id=? AND on_hand-reserved>=?",
                  q,
                  wh,
                  sku,
                  q)
              == 1,
          "Stock changed");
      s.db.update(
          "INSERT INTO reservation_lines(order_id,warehouse_id,sku_id,quantity) VALUES (?,?,?,?)",
          order,
          wh,
          sku,
          q);
      movement(wh, sku, 0, q, 0, "RESERVE", order, "Order allocation", "system");
    }
    s.db.update("UPDATE reservations SET state='ACTIVE',updated_at=now() WHERE order_id=?", order);
    events.emit(order, "orders", "StockReserved", Map.of("allocations", allocations));
  }

  void commit(String order) {
    var r =
        s.one(
            "SELECT *,expires_at>now() valid FROM reservations WHERE order_id=? FOR UPDATE", order);
    if (r.get("state").equals("ACTIVE") && Boolean.TRUE.equals(r.get("valid"))) {
      s.db.update(
          "UPDATE reservations SET state='COMMITTED',updated_at=now() WHERE order_id=?", order);
      var lines =
          s.db.queryForList(
              "SELECT warehouse_id AS \"warehouseId\",sku_id AS \"skuId\",quantity FROM reservation_lines WHERE order_id=?",
              order);
      events.emit(order, "orders", "StockCommitted", Map.of("allocations", lines));
    } else if (r.get("state").equals("ACTIVE")) release(order, "EXPIRED");
  }

  void release(String order, String state) {
    s.db.update(
        "INSERT INTO reservations(order_id,state,expires_at) VALUES (?,'PENDING',now()) ON CONFLICT DO NOTHING",
        order);
    var r = s.one("SELECT * FROM reservations WHERE order_id=? FOR UPDATE", order);
    if (state.equals("EXPIRED") && !str(r, "state").equals("ACTIVE")) return;
    if (List.of("RELEASED", "EXPIRED").contains(str(r, "state"))) {
      if (state.equals("RELEASED")) events.emit(order, "orders", "StockReleased", Map.of());
      return;
    }
    if (str(r, "state").equals("CONSUMED")) return;
    var lines =
        s.db.queryForList(
            "SELECT * FROM reservation_lines WHERE order_id=? AND NOT consumed ORDER BY warehouse_id,sku_id",
            order);
    for (var l : lines) {
      int q = number(l, "quantity");
      String wh = str(l, "warehouse_id"), sku = str(l, "sku_id");
      s.db.update(
          "UPDATE stock_balances SET reserved=reserved-?,version=version+1 WHERE warehouse_id=? AND sku_id=?",
          q,
          wh,
          sku);
      movement(wh, sku, 0, -q, 0, "RELEASE", order, state, "system");
    }
    s.db.update("UPDATE reservations SET state=?,updated_at=now() WHERE order_id=?", state, order);
    events.emit(
        order, "orders", state.equals("EXPIRED") ? "StockExpired" : "StockReleased", Map.of());
  }

  void consume(String order, String warehouse, String shipment) {
    var r = s.one("SELECT * FROM reservations WHERE order_id=? FOR UPDATE", order);
    require(
        r.get("state").equals("COMMITTED") || r.get("state").equals("CONSUMED"),
        "Reservation not committed");
    var lines =
        s.db.queryForList(
            "SELECT * FROM reservation_lines WHERE order_id=? AND warehouse_id=? AND NOT consumed ORDER BY sku_id",
            order,
            warehouse);
    for (var l : lines) {
      int q = number(l, "quantity");
      String sku = str(l, "sku_id");
      s.db.update(
          "UPDATE stock_balances SET on_hand=on_hand-?,reserved=reserved-?,version=version+1 WHERE warehouse_id=? AND sku_id=?",
          q,
          q,
          warehouse,
          sku);
      movement(warehouse, sku, -q, -q, 0, "DISPATCH", shipment, "Shipment dispatched", "system");
    }
    s.db.update(
        "UPDATE reservation_lines SET consumed=true WHERE order_id=? AND warehouse_id=?",
        order,
        warehouse);
    if (s.db.queryForObject(
            "SELECT count(*) FROM reservation_lines WHERE order_id=? AND NOT consumed",
            Integer.class,
            order)
        == 0)
      s.db.update(
          "UPDATE reservations SET state='CONSUMED',updated_at=now() WHERE order_id=?", order);
    events.emit(order, "fulfillment", "StockConsumed", Map.of("shipmentId", shipment));
  }

  void restock(Events.Event e) {
    var p = e.payload();
    String wh = str(p, "warehouseId"), sku = str(p, "skuId"), ret = str(p, "returnId");
    int q = number(p, "quantity");
    if (operation("return:" + ret, s.encode(p))) {
      ensureBalance(wh, sku);
      boolean sellable = str(p, "disposition").equals("SELLABLE");
      s.db.update(
          "UPDATE stock_balances SET on_hand=on_hand+?,damaged=damaged+?,version=version+1 WHERE warehouse_id=? AND sku_id=?",
          sellable ? q : 0,
          sellable ? 0 : q,
          wh,
          sku);
      movement(
          wh,
          sku,
          sellable ? q : 0,
          0,
          sellable ? 0 : q,
          "RETURN",
          ret,
          "Inspected return",
          "system");
    }
    events.emit(e.aggregateId(), "fulfillment", "ReturnRestocked", Map.of("returnId", ret));
  }

  @Scheduled(fixedDelay = 5000)
  @Transactional
  public void expire() {
    if (!jobs) return;
    var rows =
        s.db.queryForList(
            "SELECT order_id FROM reservations WHERE state='ACTIVE' AND expires_at<now() ORDER BY order_id LIMIT 50 FOR UPDATE SKIP LOCKED");
    for (var r : rows) release(str(r, "order_id"), "EXPIRED");
  }

  public record Transfer(
      @NotBlank String source,
      @NotBlank String destination,
      @NotBlank String skuId,
      @Min(1) @Max(100000) int quantity,
      @NotBlank String operationId) {}

  @GetMapping("/transfers")
  public Object transfers() {
    roles("ADMIN", "OPERATOR");
    return role().equals("ADMIN")
        ? s.db.queryForList("SELECT * FROM stock_transfers ORDER BY created_at DESC LIMIT 200")
        : s.db.queryForList(
            "SELECT * FROM stock_transfers WHERE source=? OR destination=? ORDER BY created_at DESC LIMIT 200",
            user().getClaimAsString("warehouse"),
            user().getClaimAsString("warehouse"));
  }

  @PostMapping("/transfers")
  @Transactional
  public Object transfer(@Valid @RequestBody Transfer r) {
    warehouse(r.source());
    require(!r.source().equals(r.destination()), "Choose different warehouses");
    if (!operation("transfer:" + r.operationId(), s.encode(r)))
      return Map.of("id", r.operationId());
    require(
        s.db.update(
                "UPDATE stock_balances SET on_hand=on_hand-?,version=version+1 WHERE warehouse_id=? AND sku_id=? AND on_hand-reserved>=?",
                r.quantity(),
                r.source(),
                r.skuId(),
                r.quantity())
            == 1,
        "Insufficient available stock");
    s.db.update(
        "INSERT INTO stock_transfers(id,source,destination,sku_id,quantity) VALUES (?,?,?,?,?)",
        r.operationId(),
        r.source(),
        r.destination(),
        r.skuId(),
        r.quantity());
    movement(
        r.source(),
        r.skuId(),
        -r.quantity(),
        0,
        0,
        "TRANSFER_OUT",
        r.operationId(),
        "Transfer dispatched",
        uid());
    return Map.of("id", r.operationId());
  }

  @PostMapping("/transfers/{id}/receive")
  @Transactional
  public Object receive(@PathVariable String id) {
    var t = s.one("SELECT * FROM stock_transfers WHERE id=? FOR UPDATE", id);
    String wh = str(t, "destination"), sku = str(t, "sku_id");
    warehouse(wh);
    if (t.get("state").equals("RECEIVED")) return t;
    int q = number(t, "quantity");
    ensureBalance(wh, sku);
    s.db.update(
        "UPDATE stock_balances SET on_hand=on_hand+?,version=version+1 WHERE warehouse_id=? AND sku_id=?",
        q,
        wh,
        sku);
    movement(wh, sku, q, 0, 0, "TRANSFER_IN", id, "Transfer received", uid());
    s.db.update("UPDATE stock_transfers SET state='RECEIVED' WHERE id=?", id);
    return Map.of("status", "RECEIVED");
  }

  @GetMapping("/reconciliation")
  public Object reconcile() {
    roles("ADMIN");
    return s.db.queryForList(
        "SELECT b.*,coalesce(m.hand,0) ledger_on_hand,coalesce(m.held,0) ledger_reserved,coalesce(m.damaged,0) ledger_damaged FROM stock_balances b LEFT JOIN (SELECT warehouse_id,sku_id,sum(on_hand_delta) hand,sum(reserved_delta) held,sum(damaged_delta) damaged FROM stock_movements GROUP BY warehouse_id,sku_id) m USING(warehouse_id,sku_id) WHERE b.on_hand<>coalesce(m.hand,0) OR b.reserved<>coalesce(m.held,0) OR b.damaged<>coalesce(m.damaged,0)");
  }
}
