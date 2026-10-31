package dev.stockflow;

import static dev.stockflow.Support.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'orders'")
public class Orders implements Events.Handler {
  private final Support s;
  private final Events events;
  private final ProductLookup products;

  public Orders(Support s, Events events, ProductLookup products) {
    this.s = s;
    this.events = events;
    this.products = products;
  }

  public String target() {
    return "orders";
  }

  public record Item(@NotBlank String skuId, @Min(1) @Max(10000) int quantity) {}

  public record Checkout(
      @NotBlank @Size(max = 500) String address,
      @NotEmpty @Size(max = 30) List<@Valid Item> items) {}

  @GetMapping
  public Object list() {
    roles("ADMIN", "CUSTOMER");
    return role().equals("ADMIN")
        ? s.db.queryForList("SELECT * FROM orders ORDER BY created_at DESC LIMIT 200")
        : s.db.queryForList(
            "SELECT * FROM orders WHERE customer_id=? ORDER BY created_at DESC LIMIT 200", uid());
  }

  @GetMapping("/{id}")
  public Object details(@PathVariable String id) {
    var o = s.one("SELECT * FROM orders WHERE id=?", id);
    owner(str(o, "customer_id"));
    o.put(
        "items",
        s.db.queryForList("SELECT * FROM order_items WHERE order_id=? ORDER BY sku_id", id));
    o.put(
        "history",
        s.db.queryForList(
            "SELECT state,note,created_at FROM order_history WHERE order_id=? ORDER BY id", id));
    return o;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Transactional
  @SuppressWarnings("unchecked")
  public Object checkout(
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Checkout request) {
    roles("CUSTOMER", "ADMIN");
    require(key.length() >= 8 && key.length() <= 100, "Idempotency key must have 8–100 characters");
    require(
        request.items().stream().map(Item::skuId).distinct().count() == request.items().size(),
        "Duplicate product lines are not allowed");
    // Serialize the same customer's key before checking it. Hash collisions only cause extra
    // waiting.
    s.db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", uid() + ":" + key);
    String body = s.encode(request);
    var existing =
        s.db.queryForList("SELECT * FROM checkout_keys WHERE customer_id=? AND key=?", uid(), key);
    if (!existing.isEmpty()) {
      require(
          existing.getFirst().get("request").equals(body),
          "Idempotency key reused with a different request");
      return details(str(existing.getFirst(), "order_id"));
    }
    String order = id();
    BigDecimal total = BigDecimal.ZERO;
    List<Map<String, Object>> items = new ArrayList<>();
    for (var item : request.items()) {
      Map<String, Object> product = products.get(item.skuId());
      BigDecimal price = new BigDecimal(product.get("price").toString());
      total = total.add(price.multiply(BigDecimal.valueOf(item.quantity())));
      items.add(
          Map.of(
              "skuId",
              item.skuId(),
              "name",
              product.get("name"),
              "price",
              price,
              "quantity",
              item.quantity()));
    }
    s.db.update(
        "INSERT INTO orders(id,customer_id,customer_email,state,total,address) VALUES (?,?,?,'PENDING_STOCK',?,?)",
        order,
        uid(),
        user().getClaimAsString("email"),
        total,
        request.address());
    for (var item : items)
      s.db.update(
          "INSERT INTO order_items(order_id,sku_id,name,price,quantity) VALUES (?,?,?,?,?)",
          order,
          item.get("skuId"),
          item.get("name"),
          item.get("price"),
          item.get("quantity"));
    s.db.update(
        "INSERT INTO checkout_keys(customer_id,key,request,order_id) VALUES (?,?,?,?)",
        uid(),
        key,
        body,
        order);
    history(order, "PENDING_STOCK", "Awaiting inventory allocation");
    events.emit(order, "inventory", "Reserve", Map.of("items", items));
    return details(order);
  }

  @PostMapping("/{id}/cancel")
  @Transactional
  public Object cancel(@PathVariable String id) {
    var o = s.one("SELECT * FROM orders WHERE id=? FOR UPDATE", id);
    owner(str(o, "customer_id"));
    String state = str(o, "state");
    if (List.of("CANCELLED", "CANCELLATION_PENDING").contains(state)) return o;
    require(
        List.of("PENDING_STOCK", "CONFIRMED", "FULFILLING").contains(state),
        "This order can no longer be cancelled");
    s.db.update("UPDATE orders SET previous_state=state WHERE id=?", id);
    transition(id, "CANCELLATION_PENDING", "Awaiting fulfillment cancellation");
    events.emit(id, "fulfillment", "CancelFulfillment", Map.of("customerId", o.get("customer_id")));
    return details(id);
  }

  public void handle(Events.Event e) {
    var o = s.one("SELECT * FROM orders WHERE id=? FOR UPDATE", e.aggregateId());
    String state = str(o, "state"), order = e.aggregateId();
    switch (e.kind()) {
      case "StockReserved" -> {
        if (state.equals("PENDING_STOCK")) events.emit(order, "inventory", "Commit", Map.of());
      }
      case "StockCommitted" -> {
        if (state.equals("PENDING_STOCK")) {
          transition(order, "CONFIRMED", "Inventory committed");
          var items =
              s.db.queryForList(
                  "SELECT sku_id AS \"skuId\",name,price,quantity FROM order_items WHERE order_id=?",
                  order);
          events.emit(
              order,
              "fulfillment",
              "CreateFulfillment",
              Map.of(
                  "customerId",
                  o.get("customer_id"),
                  "allocations",
                  e.payload().get("allocations"),
                  "items",
                  items));
          events.emit(
              order,
              "notification",
              "OrderConfirmed",
              Map.of("email", o.get("customer_email"), "orderId", order));
        }
      }
      case "StockRejected", "StockExpired" -> {
        if (state.equals("PENDING_STOCK"))
          transition(
              order,
              "REJECTED",
              e.payload().getOrDefault("reason", "Reservation expired").toString());
      }
      case "FulfillmentReady" -> {
        if (state.equals("CONFIRMED")) transition(order, "FULFILLING", "Warehouse work created");
      }
      case "FulfillmentCancelled" -> {
        if (state.equals("CANCELLATION_PENDING"))
          events.emit(order, "inventory", "Release", Map.of());
      }
      case "StockReleased" -> {
        if (state.equals("CANCELLATION_PENDING"))
          transition(order, "CANCELLED", "Inventory released");
      }
      case "CancellationRejected" -> {
        if (state.equals("CANCELLATION_PENDING"))
          transition(
              order,
              e.payload().getOrDefault("state", "FULFILLING").toString(),
              "Dispatch already started; use returns after delivery");
      }
      case "ShipmentProgress" -> {
        if (!List.of("CANCELLED", "REJECTED", "DELIVERED").contains(state)
            && !state.equals("CANCELLATION_PENDING")) {
          String next = str(e.payload(), "state");
          var rank =
              Map.of(
                  "PENDING_STOCK",
                  0,
                  "CONFIRMED",
                  1,
                  "FULFILLING",
                  2,
                  "PARTIALLY_SHIPPED",
                  3,
                  "SHIPPED",
                  4,
                  "DELIVERED",
                  5);
          if (rank.getOrDefault(next, 0) > rank.getOrDefault(state, 0))
            transition(order, next, "Shipment progress updated");
        }
      }
      default -> throw new IllegalArgumentException("Unknown order event " + e.kind());
    }
  }

  void history(String id, String state, String note) {
    s.db.update("INSERT INTO order_history(order_id,state,note) VALUES (?,?,?)", id, state, note);
  }

  void transition(String id, String state, String note) {
    s.db.update("UPDATE orders SET state=?,reason=?,updated_at=now() WHERE id=?", state, note, id);
    history(id, state, note);
  }
}
