package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import co.edu.corhuila.multi_tour.identity_audit.adapters.http.ErrorBody;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the token by itself (norm 5.3): algorithm exactly RS256, signed by the key of the security
 * microservice, {@code exp} and {@code sub} required, 30 s of tolerance. A valid one becomes the
 * {@link Caller} of the request; anything else is a 401 with the single envelope. Routes under
 * {@code /api/v1/} are protected, except the public profile; the other routes (health) are not.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

  public static final String CALLER = JwtAuthFilter.class.getName() + ".caller";
  private static final String PROTECTED_PREFIX = "/api/v1/";
  private static final String PUBLIC_PREFIX = "/api/v1/identity-audit/public/";
  private static final long LEEWAY_MILLIS = 30_000;

  private final RSASSAVerifier verifier;
  private final ObjectMapper mapper;

  JwtAuthFilter(RSAPublicKey key, ObjectMapper mapper) {
    this.verifier = new RSASSAVerifier(key);
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith(PROTECTED_PREFIX) || path.startsWith(PUBLIC_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Caller caller = caller(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (caller == null) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(), ErrorBody.of(HttpStatus.UNAUTHORIZED, MDC.get("traceId")));
      return;
    }
    request.setAttribute(CALLER, caller);
    chain.doFilter(request, response);
  }

  /** The caller of a valid token, or null for anything that is not one. */
  private Caller caller(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return null;
    }
    try {
      SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()).trim());
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
        return null;
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Date expiry = claims.getExpirationTime();
      if (expiry == null || expiry.getTime() + LEEWAY_MILLIS < System.currentTimeMillis()) {
        return null;
      }
      List<String> names = claims.getStringListClaim("roles");
      Set<Role> roles = EnumSet.noneOf(Role.class);
      for (String name : names == null ? List.<String>of() : names) {
        roles.add(Role.valueOf(name));
      }
      return new Caller(claims.getSubject(), claims.getStringClaim("tenantId"), roles);
    } catch (ParseException | JOSEException | IllegalArgumentException e) {
      return null;
    }
  }
}
