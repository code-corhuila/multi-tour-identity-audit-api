package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.core.tenant.VisualIdentity;

/** The two colors as the API writes them; the domain value is translated here and never serialized. */
public record VisualIdentityBody(String primaryColor, String secondaryColor) {

  static VisualIdentityBody of(VisualIdentity identity) {
    return identity == null ? null : new VisualIdentityBody(identity.primaryColor(), identity.secondaryColor());
  }
}