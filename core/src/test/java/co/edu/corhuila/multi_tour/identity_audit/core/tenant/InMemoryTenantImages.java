package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  @Override
  public Optional<TenantImage> find(String tenantId, ImageSlot slot, int position) {
    return Optional.ofNullable(at(tenantId, slot, position));
  }

  @Override
  public List<ImageSummary> list(String tenantId) {
    return files.values().stream().filter(i -> i.tenantId().equals(tenantId))
        .map(i -> new ImageSummary(i.slot(), i.position(), i.sha256()))
        .sorted(Comparator.comparing((ImageSummary s) -> s.slot() == ImageSlot.LOGO ? 0 : 1).thenComparing(ImageSummary::position))
        .toList();
  }

  TenantImage at(String tenantId, ImageSlot slot, int position) {
    return files.get(key(tenantId, slot, position));
  }
}
