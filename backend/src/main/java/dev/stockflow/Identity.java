package dev.stockflow;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnExpression("'${app.role}' == 'all' or '${app.role}' == 'identity'")
public class Identity {
  private final Support s;
  private final PasswordEncoder encoder;
  private final String secret;
  private final boolean secure;
  private final org.springframework.beans.factory.ObjectProvider<Inventory> inventory;
  private final org.springframework.web.client.RestClient warehouseClient;

  public Identity(
      Support s,
      PasswordEncoder encoder,
      @Value("${app.jwt-secret}") String secret,
      @Value("${app.cookie-secure}") boolean secure,
      org.springframework.beans.factory.ObjectProvider<Inventory> inventory,
      @Value("${INVENTORY_URL:http://inventory:8080}") String inventoryUrl) {
    this.s = s;
    this.encoder = encoder;
    this.secret = secret;
    this.secure = secure;
    this.inventory = inventory;
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.warehouseClient =
        org.springframework.web.client.RestClient.builder()
            .baseUrl(inventoryUrl)
            .requestFactory(factory)
            .build();
  }

  public record Login(@Email @NotBlank String email, @NotBlank String password) {}

  public record Register(
      @Email @NotBlank String email,
      @NotBlank @Size(min = 2, max = 100) String name,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  @GetMapping("/users")
  public Object users() {
    Support.roles("ADMIN");
    return s.db.queryForList(
        "SELECT id,email,name,role,warehouse FROM users ORDER BY email LIMIT 200");
  }

  public record Assignment(@NotBlank String role, String warehouse) {}

  @PutMapping("/users/{id}/assignment")
  @Transactional
  @SuppressWarnings("unchecked")
  public Object assign(@PathVariable String id, @Valid @RequestBody Assignment a) {
    Support.roles("ADMIN");
    Support.require(List.of("ADMIN", "OPERATOR", "CUSTOMER").contains(a.role()), "Invalid role");
    Support.require(
        !id.equals(Support.uid()) || a.role().equals("ADMIN"),
        "You cannot remove your own administrator role");
    if (a.role().equals("OPERATOR")) {
      List<Map<String, Object>> available =
          inventory.getIfAvailable() != null
              ? (List<Map<String, Object>>) inventory.getObject().warehouses()
              : warehouseClient
                  .get()
                  .uri("/api/inventory/warehouses")
                  .header("Authorization", "Bearer " + Support.user().getTokenValue())
                  .retrieve()
                  .body(List.class);
      Support.require(
          available.stream().anyMatch(w -> Objects.equals(w.get("id"), a.warehouse())),
          "Select an existing warehouse");
    }
    Support.require(
        s.db.update(
                "UPDATE users SET role=?,warehouse=? WHERE id=?",
                a.role(),
                a.role().equals("OPERATOR") ? a.warehouse() : null,
                id)
            == 1,
        "User not found");
    s.db.update("UPDATE refresh_sessions SET revoked=true WHERE user_id=?", id);
    return Map.of("status", "updated; existing access tokens expire within 15 minutes");
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @PostMapping("/register")
  @Transactional
  public Map<String, Object> register(
      @Valid @RequestBody Register r, HttpServletResponse response) {
    String id = Support.id();
    s.db.update(
        "INSERT INTO users(id,email,name,password_hash,role) VALUES (?,?,?,?, 'CUSTOMER')",
        id,
        r.email().toLowerCase(Locale.ROOT).trim(),
        r.name(),
        encoder.encode(r.password()));
    return session(s.one("SELECT * FROM users WHERE id=?", id), response);
  }

  @PostMapping("/login")
  @Transactional
  public Map<String, Object> login(@Valid @RequestBody Login r, HttpServletResponse response) {
    var users =
        s.db.queryForList(
            "SELECT * FROM users WHERE email=?", r.email().toLowerCase(Locale.ROOT).trim());
    if (users.isEmpty()
        || !encoder.matches(r.password(), users.getFirst().get("password_hash").toString()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    return session(users.getFirst(), response);
  }

  private void browserGuard(HttpServletRequest request) {
    // Cookies are accepted only with a custom header. No cross-origin CORS policy is enabled.
    if (!"stockflow".equals(request.getHeader("X-Stockflow-CSRF")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing refresh protection header");
    String site = request.getHeader("Sec-Fetch-Site");
    if ("cross-site".equals(site))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site session request denied");
  }

  @PostMapping("/refresh")
  @Transactional
  public Map<String, Object> refresh(
      @CookieValue(name = "sf_refresh", defaultValue = "") String token,
      HttpServletRequest req,
      HttpServletResponse res) {
    browserGuard(req);
    var sessions =
        s.db.queryForList(
            "SELECT * FROM refresh_sessions WHERE token_hash=? AND NOT revoked AND expires_at>now() FOR UPDATE",
            hash(token));
    if (sessions.isEmpty())
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired");
    s.db.update("UPDATE refresh_sessions SET revoked=true WHERE token_hash=?", hash(token));
    return session(
        s.one("SELECT * FROM users WHERE id=?", sessions.getFirst().get("user_id")), res);
  }

  @PostMapping("/logout")
  @Transactional
  public Map<String, String> logout(
      @CookieValue(name = "sf_refresh", defaultValue = "") String token,
      HttpServletRequest req,
      HttpServletResponse res) {
    browserGuard(req);
    s.db.update("UPDATE refresh_sessions SET revoked=true WHERE token_hash=?", hash(token));
    cookie(res, "", 0);
    return Map.of("status", "signed out");
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return s.one("SELECT id,email,name,role,warehouse FROM users WHERE id=?", Support.uid());
  }

  private void cookie(HttpServletResponse response, String value, long seconds) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("sf_refresh", value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api/auth")
            .maxAge(seconds)
            .build()
            .toString());
  }

  private Map<String, Object> session(Map<String, Object> u, HttpServletResponse response) {
    try {
      var claims =
          new JWTClaimsSet.Builder()
              .subject(u.get("id").toString())
              .issuer("stockflow")
              .audience("stockflow-api")
              .issueTime(new Date())
              .expirationTime(Date.from(Instant.now().plusSeconds(900)))
              .claim("role", u.get("role"))
              .claim("email", u.get("email"))
              .claim("warehouse", u.get("warehouse"))
              .build();
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(secret));
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      s.db.update(
          "INSERT INTO refresh_sessions(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '7 days')",
          hash(refresh),
          u.get("id"));
      cookie(response, refresh, 604800);
      var profile = new LinkedHashMap<String, Object>();
      for (String k : List.of("id", "name", "email", "role", "warehouse")) profile.put(k, u.get(k));
      return Map.of("accessToken", jwt.serialize(), "user", profile);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
