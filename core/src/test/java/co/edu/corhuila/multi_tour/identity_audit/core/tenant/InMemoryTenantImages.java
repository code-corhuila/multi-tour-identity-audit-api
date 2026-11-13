package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.HashMap;
import java.util.Map;

/** The files of the tenants kept in memory, one per tenant, kind and position, as the database keeps them. */
final class InMemoryTenantImages implements TenantImages {

  final Map<String, TenantImage> files = new HashMap<>();

  static String key(String tenantId, ImageSlot slot, int position) {
    return tenantId + "|" + slot + "|" + position;
  }

  @Override
  public void save(TenantImage image) {
    files.put(key(image.tenantId(), image.slot(), image.position()), image);
  }

  @Override
  public void remove(String tenantId, ImageSlot slot, int position) {
    files.remove(key(tenantId, slot, position));
  }

  TenantImage at(String tenantId, ImageSlot slot, int position) {
    return files.get(key(tenantId, slot, position));
  }
}
