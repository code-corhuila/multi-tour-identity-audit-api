package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** The events a tenant leaves for the rest of the platform (ADR-004 D4, plan D-06). */
public enum TenantEventType {
  TENANT_CREATED("TenantCreated"),
  TENANT_INACTIVATED("TenantInactivated"),
  TENANT_REACTIVATED("TenantReactivated"),
  /** The zone or the country changed, forward only (spec 010 of the API, HU-IDA-004). */
  TENANT_LOCALIZATION_CHANGED("TenantLocalizationChanged");

  private final String eventType;

  TenantEventType(String eventType) {
    this.eventType = eventType;
  }

  public String eventType() {
    return eventType;
  }
}
