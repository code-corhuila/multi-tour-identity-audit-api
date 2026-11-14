package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.time.Clock;
import java.util.List;

/**
 * The Tenant Administrator changes the official zone, the country, or both, of their own company, from now on (spec 010, HU-IDA-004).
 * The zone cannot change while a cash box is open or pending closing, and the state of the cash boxes is read at that moment: if it
 * cannot be read nothing changes. The change and its event are written together; a request that raced with another decides once more.
 */
public final class ChangeTenantLocalization {

  public record Command(Caller caller, String timeZone, String country, String correlationId) {}

  private final TenantRepository tenants;
  private final CashBoxes cashBoxes;
  private final IdGenerator ids;
  private final Clock clock;

  public ChangeTenantLocalization(TenantRepository tenants, CashBoxes cashBoxes, IdGenerator ids, Clock clock) {
    this.tenants = tenants;
    this.cashBoxes = cashBoxes;
    this.ids = ids;
    this.clock = clock;
  }

  public Tenant execute(Command command) {
    Caller caller = command.caller();
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    if (command.timeZone() == null && command.country() == null) {
      throw new InvalidDataException("timeZone", "send the time zone, the country or both");
    }
    String actor = Tenant.text(caller.subject(), 100, "The actor");
    TenantEvent.requireWho(actor, command.correlationId());
    try {
      return attempt(command, actor);
    } catch (ConcurrentChangeException raced) {
      return attempt(command, actor);
    }
  }

  private Tenant attempt(Command command, String actor) {
    String tenantId = command.caller().tenantId();
    Tenant current = tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    String zone = command.timeZone() == null ? current.timeZone() : command.timeZone();
    String country = command.country() == null ? current.country() : command.country();
    // the values are checked, and an inactive tenant refused, before anything is read from another service
    Tenant next = current.withLocalization(zone, country, clock.instant());
    if (zone.equals(current.timeZone()) && country.equals(current.country())) {
      return current;
    }
    if (!zone.equals(current.timeZone()) && cashBoxes.anyOpenOrPendingClosing(tenantId)) {
      throw new BusinessRuleViolationException("The time zone cannot change while a cash box is open or pending closing");
    }
    TenantEvent event = new TenantEvent(
        ids.newId(), TenantEventType.TENANT_LOCALIZATION_CHANGED, next, actor, command.correlationId(), next.updatedAt(), current);
    tenants.update(current, next, List.of(event));
    return next;
  }
}
