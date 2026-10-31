package dev.stockflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Support {
  public final JdbcTemplate db;
  public final ObjectMapper json;

  public Support(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
    return rows.getFirst();
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String str(Map<String, ?> m, String key) {
    var v = m.get(key);
    if (v == null) throw new IllegalArgumentException("Missing " + key);
    return v.toString();
  }

  public static int number(Map<String, ?> m, String key) {
    return Integer.parseInt(str(m, key));
  }

  public static void require(boolean ok, String message) {
    if (!ok) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  public static Jwt user() {
    return (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  public static String uid() {
    return user().getSubject();
  }

  public static String role() {
    return user().getClaimAsString("role");
  }

  public static void roles(String... permitted) {
    if (!Arrays.asList(permitted).contains(role()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted");
  }

  public static void owner(String owner) {
    if (!role().equals("ADMIN") && !uid().equals(owner))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your record");
  }

  public static void warehouse(String warehouse) {
    roles("ADMIN", "OPERATOR");
    if (role().equals("OPERATOR")
        && !Objects.equals(user().getClaimAsString("warehouse"), warehouse))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Warehouse not assigned");
  }
}
