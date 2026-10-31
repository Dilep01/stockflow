package dev.stockflow;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestCorrelation extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader("X-Request-ID");
    if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) id = Support.id();
    response.setHeader("X-Request-ID", id);
    try (var ignored = MDC.putCloseable("correlationId", id)) {
      chain.doFilter(request, response);
    }
  }
}
