package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import co.edu.corhuila.multi_tour.identity_audit.adapters.http.ErrorBody;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoff;
import co.edu.corhuila.multi_tour.identity_audit.core.session.SessionCutoffs;
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
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the token by itself (norm 5.3): algorithm exactly RS256, signed by the key of the security
 * microservice, {@code exp} and {@code sub} required, 30 s of tolerance. A token issued before the cutoff of its
 * account ({@code AccountSessionsRevoked}, ADR-004 D5) is refused too. A valid one becomes the
 * {@link Caller} of the request; anything else is a 401 with the single envelope. Which routes need
 * one is decided by {@link RouteGuard}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

  public static final String CALLER = JwtAuthFilter.class.getName() + ".caller";
  private static final long LEEWAY_MILLIS = 30_000;

  /** What an event keeps of its actor: the subject of the token is recorded as it comes. */
  private static final int MAX_SUBJECT = 100;

  private final RSASSAVerifier verifier;
  private final ObjectMapper mapper;
  private final String issuer;
  private final String audience;
  private final SessionCutoffs cutoffs;

  /** {@code issuer} and {@code audience} are checked only when the operator sets them (JWT_ISSUER, JWT_AUDIENCE). */
  JwtAuthFilter(
      RSAPublicKey key, ObjectMapper mapper, @Value("${JWT_ISSUER:}") String issuer,
      @Value("${JWT_AUDIENCE:}") String audience, SessionCutoffs cutoffs) {
    this.cutoffs = cutoffs;
    this.verifier = new RSASSAVerifier(key);
    this.mapper = mapper;
    this.issuer = issuer.strip();
    this.audience = audience.strip();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !RouteGuard.needsToken(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Verified verified = verified(request.getHeader(HttpHeaders.AUTHORIZATION));
    // the lookup is outside the parsing on purpose: a fault of the database is a 500, never a token let through
    Caller caller = verified == null || revoked(verified) ? null : verified.caller();
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

  /** A valid token: who it names and when it was issued ({@code null} when it carries no {@code iat}). */
  private record Verified(Caller caller, Date issuedAt) {}

  /**
   * True when the account has a cutoff and the token was not issued at or after it. Compared at the precision of the
   * token (seconds): a sign-in made in the second of the revocation is a new session, not an old one. A token with no
   * {@code iat} cannot be shown to be later, so it is refused when its account has a cutoff.
   */
  private boolean revoked(Verified token) {
    return cutoffs.find(token.caller().subject())
        .map(SessionCutoff::revokedFrom)
        .map(from -> token.issuedAt() == null || token.issuedAt().toInstant().isBefore(from.truncatedTo(ChronoUnit.SECONDS)))
        .orElse(false);
  }

  /** The caller of a valid token, or null for anything that is not one. */
  private Verified verified(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    try {
      SignedJWT jwt = SignedJWT.parse(authorization.substring(7).trim());
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
        return null;
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Date expiry = claims.getExpirationTime();
      if (expiry == null || expiry.getTime() + LEEWAY_MILLIS < System.currentTimeMillis()) {
        return null;
      }
      Date notBefore = claims.getNotBeforeTime();
      if (notBefore != null && notBefore.getTime() - LEEWAY_MILLIS > System.currentTimeMillis()) {
        return null;
      }
      if (!issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
        return null;
      }
      if (!audience.isBlank() && (claims.getAudience() == null || !claims.getAudience().contains(audience))) {
        return null;
      }
      String subject = claims.getSubject();
      if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT || !subject.equals(subject.strip())
          || subject.codePoints().anyMatch(JwtAuthFilter::disguises)) {
        return null;
      }
      // only the marks this service understands pass: `restricted` (it still opens nothing here) and none. The token of a
      // temporary credential (`password-change`) only serves the route that chooses a password, in the security service
      String access = claims.getStringClaim("access");
      if (access != null && !"restricted".equals(access)) {
        return null;
      }
      List<String> names = claims.getStringListClaim("roles");
      Set<Role> roles = EnumSet.noneOf(Role.class);
      for (String name : names == null ? List.<String>of() : names) {
        roles.add(Role.valueOf(name));
      }
      return new Verified(new Caller(subject, claims.getStringClaim("tenantId"), roles), claims.getIssueTime());
    } catch (ParseException | JOSEException | RuntimeException e) {
      // anything a hostile but signed token can provoke is a refusal, never a server error
      return null;
    }
  }

  /** A subject is recorded as the actor of an event, so it cannot hold what disguises a line of the log. */
  private static boolean disguises(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
      default -> false;
    };
  }
}
