package dev.stockflow;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductLookup {
  private final ObjectProvider<Catalog> catalog;
  private final RestClient remote;

  public ProductLookup(
      ObjectProvider<Catalog> catalog, @Value("${CATALOG_URL:http://catalog:8080}") String url) {
    this.catalog = catalog;
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    remote = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> get(String sku) {
    return catalog.getIfAvailable() != null
        ? catalog.getObject().product(sku)
        : remote
            .get()
            .uri("/api/catalog/products/{id}", sku)
            .header("Authorization", "Bearer " + Support.user().getTokenValue())
            .retrieve()
            .body(Map.class);
  }
}
