package dev.stockflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/catalog")
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'catalog'")
public class Catalog {
  private final Support s;
  private final StringRedisTemplate redis;
  private final boolean cache;

  public Catalog(Support s, StringRedisTemplate redis, @Value("${app.cache}") boolean cache) {
    this.s = s;
    this.redis = redis;
    this.cache = cache;
  }

  @GetMapping("/products")
  public Object list() {
    if (cache)
      try {
        String data = redis.opsForValue().get("sf:products:v1");
        if (data != null) return s.json.readTree(data);
      } catch (Exception ignored) {
      }
    var products = s.db.queryForList("SELECT * FROM products WHERE active ORDER BY name");
    if (cache)
      try {
        redis.opsForValue().set("sf:products:v1", s.encode(products), Duration.ofSeconds(30));
      } catch (Exception ignored) {
      }
    return products;
  }

  @GetMapping("/products/{id}")
  public Map<String, Object> product(@PathVariable String id) {
    return s.one("SELECT * FROM products WHERE id=? AND active", id);
  }

  public record Product(
      @NotBlank @Size(max = 50) String sku,
      @NotBlank @Size(max = 150) String name,
      @NotNull @Size(max = 2000) String description,
      @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
      @NotBlank @Size(max = 50) String category) {}

  @PostMapping("/products")
  @Transactional
  public Map<String, Object> create(@Valid @RequestBody Product p) {
    Support.roles("ADMIN");
    String id = Support.id();
    s.db.update(
        "INSERT INTO products(id,sku,name,description,price,category) VALUES (?,?,?,?,?,?)",
        id,
        p.sku(),
        p.name(),
        p.description(),
        p.price(),
        p.category());
    invalidate();
    return product(id);
  }

  @PutMapping("/products/{id}")
  @Transactional
  public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody Product p) {
    Support.roles("ADMIN");
    Support.require(
        s.db.update(
                "UPDATE products SET sku=?,name=?,description=?,price=?,category=?,version=version+1 WHERE id=?",
                p.sku(),
                p.name(),
                p.description(),
                p.price(),
                p.category(),
                id)
            == 1,
        "Product not found");
    invalidate();
    return product(id);
  }

  private void invalidate() {
    if (cache)
      try {
        redis.delete("sf:products:v1");
      } catch (Exception ignored) {
      }
  }
}
