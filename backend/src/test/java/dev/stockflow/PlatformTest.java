package dev.stockflow;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.jobs=false",
      "app.cache=false",
      "app.jwt-secret=test-only-key-32-characters-long-123456789",
      "app.demo-password=StockflowDemo2026!",
      "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:55432/stockflow_test}",
      "spring.datasource.username=sf_stockflow",
      "spring.datasource.password=${TEST_DB_PASSWORD:local-test-password}"
    })
class PlatformTest {
  @Autowired Identity identity;
  @Autowired Support s;
  @Autowired Dispatcher dispatcher;
  @Autowired Inventory inventory;
  @Autowired Orders orders;
  @Autowired Fulfillment fulfillment;
  @Autowired Seed seed;
  @Autowired PlatformTransactionManager manager;
  @Autowired TestRestTemplate http;

  @BeforeEach
  void reset() {
    String db = s.db.queryForObject("SELECT current_database()", String.class);
    assertThat(db).endsWith("_test");
    s.db.execute(
        "TRUNCATE outbox_events,processed_messages,notifications,return_requests,shipment_events,shipment_items,shipments,fulfillment_orders,checkout_keys,order_history,order_items,orders,reservation_lines,reservations,stock_movements,stock_balances,stock_transfers,inventory_operations,warehouses,refresh_sessions,users,products RESTART IDENTITY CASCADE");
    seed.run(null);
    as("ADMIN", "admin", null);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  void as(String role, String id, String warehouse) {
    var builder =
        Jwt.withTokenValue("test")
            .header("alg", "HS256")
            .subject(id)
            .claim("role", role)
            .claim("email", id + "@stockflow.local");
    if (warehouse != null) builder.claim("warehouse", warehouse);
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(builder.build()));
  }

  void event(String order, String target, String kind, Map<String, Object> payload) {
    dispatcher.deliver(new Events.Event(Support.id(), order, target, kind, payload));
  }

  Map<String, Object> item(String sku, int q) {
    return Map.of("skuId", sku, "quantity", q);
  }

  void reserve(String order, String sku, int q) {
    event(order, "inventory", "Reserve", Map.of("items", List.of(item(sku, q))));
  }

  void drain() {
    for (int round = 0; round < 100; round++) {
      var rows =
          s.db.queryForList(
              "SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at,id");
      if (rows.isEmpty()) return;
      for (var r : rows) {
        dispatcher.deliver(
            new Events.Event(
                r.get("id").toString(),
                r.get("aggregate_id").toString(),
                r.get("target").toString(),
                r.get("kind").toString(),
                s.decode(r.get("payload").toString())));
        s.db.update("UPDATE outbox_events SET published_at=now() WHERE id=?", r.get("id"));
      }
    }
    throw new AssertionError("Workflow did not converge");
  }

  String order(int quantity) {
    var result =
        (Map<?, ?>)
            orders.checkout(
                Support.id(),
                new Orders.Checkout("Demo address", List.of(new Orders.Item("SKU-KEY", quantity))));
    return result.get("id").toString();
  }

  int held() {
    return s.db.queryForObject("SELECT sum(reserved) FROM stock_balances", Integer.class);
  }

  void assertLedger() {
    as("ADMIN", "admin", null);
    assertThat((List<?>) inventory.reconcile()).isEmpty();
  }

  @Test
  void concurrentReservationsDoNotOversell() throws Exception {
    s.db.update("UPDATE stock_balances SET on_hand=0 WHERE sku_id='SKU-KEY'");
    s.db.update(
        "UPDATE stock_balances SET on_hand=10 WHERE sku_id='SKU-KEY' AND warehouse_id='WH-BLR'");
    try (var pool = Executors.newFixedThreadPool(16)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 100; i++)
        tasks.add(
            () -> {
              reserve(Support.id(), "SKU-KEY", 1);
              return null;
            });
      for (var result : pool.invokeAll(tasks)) result.get();
    }
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM reservations WHERE state='ACTIVE'", Integer.class))
        .isEqualTo(10);
    assertThat(held()).isEqualTo(10);
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM stock_balances WHERE reserved>on_hand OR reserved<0",
                Integer.class))
        .isZero();
  }

  @Test
  void unavailableLineLeavesNoPartialReservation() {
    event(
        "multi",
        "inventory",
        "Reserve",
        Map.of("items", List.of(item("SKU-KEY", 2), item("missing", 1))));
    assertThat(held()).isZero();
    assertThat(s.one("SELECT * FROM reservations WHERE order_id='multi'").get("state"))
        .isEqualTo("REJECTED");
    assertLedger();
  }

  @Test
  void allocationPrefersSingleWarehouse() {
    reserve("single", "SKU-KEY", 10);
    assertThat(
            s.db.queryForList(
                "SELECT DISTINCT warehouse_id FROM reservation_lines WHERE order_id='single'",
                String.class))
        .containsExactly("WH-HYD");
    assertLedger();
  }

  @Test
  void largeOrderSplitsAcrossWarehouses() {
    reserve("split", "SKU-KEY", 20);
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM reservation_lines WHERE order_id='split'", Integer.class))
        .isEqualTo(2);
    assertThat(held()).isEqualTo(20);
    assertLedger();
  }

  @Test
  void duplicateMessageIsAppliedOnce() {
    var e =
        new Events.Event(
            "same", "dup", "inventory", "Reserve", Map.of("items", List.of(item("SKU-KEY", 3))));
    dispatcher.deliver(e);
    dispatcher.deliver(e);
    assertThat(held()).isEqualTo(3);
    assertLedger();
  }

  @Test
  void releaseBeforeReserveBlocksLateReservation() {
    event("late", "inventory", "Release", Map.of());
    reserve("late", "SKU-KEY", 5);
    assertThat(held()).isZero();
    assertThat(s.one("SELECT * FROM reservations WHERE order_id='late'").get("state"))
        .isEqualTo("RELEASED");
  }

  @Test
  void releaseTwiceDoesNotDoubleRelease() {
    reserve("release", "SKU-KEY", 3);
    event("release", "inventory", "Release", Map.of());
    event("release", "inventory", "Release", Map.of());
    assertThat(held()).isZero();
    assertLedger();
  }

  @Test
  void expiredReservationCannotCommit() {
    reserve("expired", "SKU-KEY", 3);
    s.db.update(
        "UPDATE reservations SET expires_at=now()-interval '1 second' WHERE order_id='expired'");
    event("expired", "inventory", "Commit", Map.of());
    assertThat(held()).isZero();
    assertThat(s.one("SELECT * FROM reservations WHERE order_id='expired'").get("state"))
        .isEqualTo("EXPIRED");
    assertLedger();
  }

  @Test
  void committedReservationDoesNotExpireOnRepeatedCommit() {
    reserve("committed", "SKU-KEY", 3);
    event("committed", "inventory", "Commit", Map.of());
    s.db.update(
        "UPDATE reservations SET expires_at=now()-interval '1 day' WHERE order_id='committed'");
    event("committed", "inventory", "Commit", Map.of());
    assertThat(held()).isEqualTo(3);
    assertLedger();
  }

  @Test
  void checkoutRetryReturnsSameOrder() {
    var req = new Orders.Checkout("Address", List.of(new Orders.Item("SKU-KEY", 2)));
    var first = (Map<?, ?>) orders.checkout("retry-key-123", req);
    var second = (Map<?, ?>) orders.checkout("retry-key-123", req);
    assertThat(first.get("id")).isEqualTo(second.get("id"));
    assertThat(s.db.queryForObject("SELECT count(*) FROM orders", Integer.class)).isOne();
    assertThatThrownBy(
            () -> orders.checkout("retry-key-123", new Orders.Checkout("Different", req.items())))
        .hasMessageContaining("different request");
  }

  @Test
  void duplicateCheckoutLinesAreRejected() {
    assertThatThrownBy(
            () ->
                orders.checkout(
                    "duplicate-lines",
                    new Orders.Checkout(
                        "Address",
                        List.of(new Orders.Item("SKU-KEY", 1), new Orders.Item("SKU-KEY", 1)))))
        .hasMessageContaining("Duplicate product");
  }

  @Test
  void fullOrderShipmentAndReturnWorkflow() {
    String id = order(20);
    drain();
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("FULFILLING");
    var ships = s.db.queryForList("SELECT * FROM shipments WHERE order_id=?", id);
    assertThat(ships).hasSize(2);
    for (var sh : ships) {
      String shipment = sh.get("id").toString();
      for (String action : List.of("pick", "pack", "dispatch")) {
        fulfillment.action(shipment, new Fulfillment.ShipmentAction(action));
        drain();
      }
      fulfillment.action(shipment, new Fulfillment.ShipmentAction("transit"));
      fulfillment.action(shipment, new Fulfillment.ShipmentAction("deliver"));
      drain();
    }
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("DELIVERED");
    assertThat(held()).isZero();
    String ret = Support.id(), shipment = ships.getFirst().get("id").toString();
    fulfillment.requestReturn(
        new Fulfillment.ReturnRequest(shipment, "SKU-KEY", 1, "Demo return", ret));
    for (String action : List.of("approve", "receive", "inspect"))
      fulfillment.returnAction(ret, new Fulfillment.ReturnAction(action, "SELLABLE"));
    drain();
    assertThat(s.one("SELECT * FROM return_requests WHERE id=?", ret).get("state"))
        .isEqualTo("CLOSED");
    assertThat(s.one("SELECT * FROM return_requests WHERE id=?", ret).get("refund"))
        .isEqualTo(new BigDecimal("3499.00"));
    assertLedger();
  }

  @Test
  void cancellationBeforeWorkersRunLeavesNoStockHeld() {
    String id = order(3);
    orders.cancel(id);
    drain();
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("CANCELLED");
    assertThat(held()).isZero();
    assertLedger();
  }

  @Test
  void cancellationOfConfirmedOrderReleasesStock() {
    String id = order(3);
    drain();
    orders.cancel(id);
    drain();
    assertThat(held()).isZero();
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("CANCELLED");
    assertLedger();
  }

  @Test
  void dispatchWinsCancellationRace() {
    String id = order(3);
    drain();
    String sh = s.db.queryForObject("SELECT id FROM shipments WHERE order_id=?", String.class, id);
    fulfillment.action(sh, new Fulfillment.ShipmentAction("pick"));
    fulfillment.action(sh, new Fulfillment.ShipmentAction("pack"));
    fulfillment.action(sh, new Fulfillment.ShipmentAction("dispatch"));
    orders.cancel(id);
    drain();
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("SHIPPED");
    assertLedger();
  }

  @Test
  void duplicateRestockDoesNotIncreaseStockTwice() {
    var payload =
        Map.<String, Object>of(
            "warehouseId",
            "WH-BLR",
            "skuId",
            "SKU-KEY",
            "quantity",
            2,
            "returnId",
            "ret-1",
            "disposition",
            "DAMAGED");
    event("o", "inventory", "Restock", payload);
    event("o", "inventory", "Restock", payload);
    assertThat(
            s.one("SELECT * FROM stock_balances WHERE warehouse_id='WH-BLR' AND sku_id='SKU-KEY'")
                .get("damaged"))
        .isEqualTo(2);
    assertLedger();
  }

  @Test
  void transferRequiresReceiptBeforeDestinationGainsStock() {
    var r = new Inventory.Transfer("WH-BLR", "WH-PUN", "SKU-KEY", 3, Support.id());
    inventory.transfer(r);
    inventory.transfer(r);
    assertThat(
            s.one("SELECT * FROM stock_balances WHERE warehouse_id='WH-PUN' AND sku_id='SKU-KEY'")
                .get("on_hand"))
        .isEqualTo(12);
    inventory.receive(r.operationId());
    inventory.receive(r.operationId());
    assertThat(
            s.one("SELECT * FROM stock_balances WHERE warehouse_id='WH-PUN' AND sku_id='SKU-KEY'")
                .get("on_hand"))
        .isEqualTo(15);
    assertLedger();
  }

  @Test
  void adjustmentCannotConsumeReservedStock() {
    reserve("adjust", "SKU-KEY", 8);
    assertThatThrownBy(
            () ->
                inventory.adjust(
                    new Inventory.Adjustment("WH-BLR", "SKU-KEY", -1, "bad", Support.id())))
        .hasMessageContaining("reserved stock");
    assertLedger();
  }

  @Test
  void operatorCannotMutateAnotherWarehouse() {
    as("OPERATOR", "operator", "WH-BLR");
    assertThatThrownBy(
            () ->
                inventory.adjust(
                    new Inventory.Adjustment("WH-PUN", "SKU-KEY", 1, "receipt", Support.id())))
        .hasMessageContaining("Warehouse not assigned");
  }

  @Test
  void customerCannotReadAnotherCustomersOrder() {
    String id = order(1);
    as("CUSTOMER", "other", null);
    assertThatThrownBy(() -> orders.details(id)).hasMessageContaining("Not your record");
  }

  @Test
  void realJwtAuthenticationAndValidation() {
    assertThat(http.getForEntity("/api/orders", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    var login =
        http.postForEntity(
            "/api/auth/login",
            Map.of("email", "admin@stockflow.local", "password", "StockflowDemo2026!"),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    var headers = new HttpHeaders();
    headers.setBearerAuth(login.getBody().get("accessToken").toString());
    assertThat(
            http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    headers.setBearerAuth("tampered");
    assertThat(
            http.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void invalidMessageRollsBackInbox() {
    var e = new Events.Event("invalid-event", "invalid", "inventory", "Unknown", Map.of());
    assertThatThrownBy(() -> dispatcher.deliver(e));
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM processed_messages WHERE event_id='invalid-event'",
                Integer.class))
        .isZero();
  }

  @Test
  void databaseRejectsNegativeStock() {
    assertThatThrownBy(
        () ->
            s.db.update(
                "UPDATE stock_balances SET on_hand=-1 WHERE warehouse_id='WH-BLR' AND sku_id='SKU-KEY'"));
  }

  @Test
  void administratorCanAssignExistingWarehouseAndCannotDemoteSelf() {
    identity.assign("customer", new Identity.Assignment("OPERATOR", "WH-PUN"));
    assertThat(s.one("SELECT * FROM users WHERE id='customer'").get("warehouse"))
        .isEqualTo("WH-PUN");
    assertThatThrownBy(
            () -> identity.assign("customer", new Identity.Assignment("OPERATOR", "missing")))
        .hasMessageContaining("existing warehouse");
    assertThatThrownBy(() -> identity.assign("admin", new Identity.Assignment("CUSTOMER", null)))
        .hasMessageContaining("own administrator");
  }

  @Test
  void unknownProductCannotCreateStock() {
    assertThatThrownBy(
            () ->
                inventory.adjust(
                    new Inventory.Adjustment(
                        "WH-BLR", "missing-product", 1, "receipt", Support.id())))
        .hasMessageContaining("Record not found");
  }

  @Test
  void fractionalStockQuantityIsRejectedAtHttpBoundary() {
    var login =
        http.postForEntity(
            "/api/auth/login",
            Map.of("email", "admin@stockflow.local", "password", "StockflowDemo2026!"),
            Map.class);
    var h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(login.getBody().get("accessToken").toString());
    var body =
        Map.of(
            "warehouseId",
            "WH-BLR",
            "skuId",
            "SKU-KEY",
            "quantity",
            1.5,
            "reason",
            "invalid",
            "operationId",
            Support.id());
    assertThat(
            http.exchange(
                    "/api/inventory/adjustments",
                    HttpMethod.POST,
                    new HttpEntity<>(body, h),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void ledgerCannotBeEdited() {
    assertThatThrownBy(() -> s.db.update("UPDATE stock_movements SET reason='rewritten'"));
    assertThatThrownBy(() -> s.db.update("DELETE FROM stock_movements"));
  }

  @Test
  void expiredThenCancelledOrderConverges() {
    String id = order(1);
    var reserve = s.one("SELECT * FROM outbox_events WHERE aggregate_id=? AND kind='Reserve'", id);
    dispatcher.deliver(
        new Events.Event(
            reserve.get("id").toString(),
            id,
            "inventory",
            "Reserve",
            s.decode(reserve.get("payload").toString())));
    s.db.update("UPDATE outbox_events SET published_at=now() WHERE id=?", reserve.get("id"));
    s.db.update(
        "UPDATE reservations SET expires_at=now()-interval '1 second' WHERE order_id=?", id);
    event(id, "inventory", "Commit", Map.of());
    orders.cancel(id);
    drain();
    assertThat(s.one("SELECT * FROM orders WHERE id=?", id).get("state")).isEqualTo("CANCELLED");
    assertThat(held()).isZero();
    assertLedger();
  }

  @Test
  void expiryAndCommitCompeteSafely() throws Exception {
    reserve("race", "SKU-KEY", 2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> event("race", "inventory", "Commit", Map.of()));
      var b =
          pool.submit(
              () ->
                  new TransactionTemplate(manager)
                      .execute(
                          status -> {
                            inventory.release("race", "EXPIRED");
                            return null;
                          }));
      a.get();
      b.get();
    }
    assertThat(held()).isIn(0, 2);
    assertLedger();
  }

  @Test
  void refreshRequiresHeaderAndRotatesToken() {
    var login =
        http.postForEntity(
            "/api/auth/login",
            Map.of("email", "customer@stockflow.local", "password", "StockflowDemo2026!"),
            Map.class);
    String cookie = login.getHeaders().getFirst("Set-Cookie").split(";")[0];
    var h = new HttpHeaders();
    h.add("Cookie", cookie);
    assertThat(
            http.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(h), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    h.add("X-Stockflow-CSRF", "stockflow");
    assertThat(
            http.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(h), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            http.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(h), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void stockChangesAndOutboxRollbackTogether() {
    assertThatThrownBy(
        () ->
            new TransactionTemplate(manager)
                .execute(
                    status -> {
                      inventory.reserve("rollback", List.of(item("SKU-KEY", 2)));
                      throw new IllegalStateException("crash before commit");
                    }));
    assertThat(held()).isZero();
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM reservations WHERE order_id='rollback'", Integer.class))
        .isZero();
    assertThat(
            s.db.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id='rollback'", Integer.class))
        .isZero();
  }
}
