package dev.stockflow;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Seed implements ApplicationRunner {
  private final Support s;
  private final PasswordEncoder passwords;
  private final String role, password;
  private final boolean enabled;

  public Seed(
      Support s,
      PasswordEncoder passwords,
      @Value("${app.role}") String role,
      @Value("${app.demo-password}") String password,
      @Value("${app.seed}") boolean enabled) {
    this.s = s;
    this.passwords = passwords;
    this.role = role;
    this.password = password;
    this.enabled = enabled;
  }

  boolean has(String r) {
    return role.equals("all") || role.equals(r);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    if (password.length() < 10)
      throw new IllegalArgumentException("DEMO_PASSWORD must be at least 10 characters");
    if (has("identity"))
      for (String r : List.of("ADMIN", "OPERATOR", "CUSTOMER"))
        s.db.update(
            "INSERT INTO users(id,email,name,password_hash,role,warehouse) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            r.toLowerCase(),
            r.toLowerCase() + "@stockflow.local",
            r.charAt(0) + r.substring(1).toLowerCase(),
            passwords.encode(password),
            r,
            r.equals("OPERATOR") ? "WH-BLR" : null);
    String[][] products = {
      {
        "SKU-KEY",
        "KEY-001",
        "Mechanical keyboard",
        "Quiet switches, wired USB-C keyboard",
        "3499.00",
        "Accessories"
      },
      {
        "SKU-MOUSE",
        "MOU-001",
        "Wireless mouse",
        "Ergonomic mouse with USB receiver",
        "1299.00",
        "Accessories"
      },
      {"SKU-HUB", "HUB-001", "USB-C hub", "Six ports for everyday work", "2199.00", "Accessories"},
      {
        "SKU-MON",
        "MON-001",
        "27-inch monitor",
        "IPS display for a clear workspace",
        "15999.00",
        "Displays"
      },
      {
        "SKU-STAND",
        "STD-001",
        "Laptop stand",
        "Adjustable aluminum desk stand",
        "1899.00",
        "Workspace"
      },
      {
        "SKU-CAM",
        "CAM-001",
        "HD webcam",
        "1080p camera with privacy cover",
        "2799.00",
        "Accessories"
      }
    };
    if (has("catalog"))
      for (var p : products)
        s.db.update(
            "INSERT INTO products(id,sku,name,description,price,category) VALUES (?,?,?,?,?::numeric,?) ON CONFLICT DO NOTHING",
            (Object[]) p);
    if (has("inventory")) {
      String[][] warehouses = {
        {"WH-BLR", "Bengaluru Central", "Bengaluru"},
        {"WH-HYD", "Hyderabad North", "Hyderabad"},
        {"WH-PUN", "Pune West", "Pune"}
      };
      int priority = 0;
      for (var w : warehouses) {
        s.db.update(
            "INSERT INTO warehouses(id,name,city,priority) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            w[0],
            w[1],
            w[2],
            priority++);
        for (var p : products) {
          int qty = w[0].equals("WH-BLR") ? 8 : 12;
          if (s.db.update(
                  "INSERT INTO stock_balances(warehouse_id,sku_id,on_hand) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                  w[0],
                  p[0],
                  qty)
              == 1)
            s.db.update(
                "INSERT INTO stock_movements(id,warehouse_id,sku_id,on_hand_delta,kind,reference,reason,actor) VALUES (?,?,?,?,'SEED','demo','Synthetic opening stock','seed')",
                Support.id(),
                w[0],
                p[0],
                qty);
        }
      }
    }
  }
}
