package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import co.edu.corhuila.multi_tour.identity_audit.adapters.security.JwtAuthFilter;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.CompleteOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailOnboarding;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.FailedStep;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.InvalidDataException;
import co.edu.corhuila.multi_tour.identity_audit.core.tenant.TenantStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Step 3 of onboard-tenant and its compensation; the role SERVICE is judged before this runs. */
@RestController
@RequestMapping("/api/v1/identity-audit/internal/tenants/{tenantId}")
class InternalOnboardingController {

  private final CompleteOnboarding complete;
  private final FailOnboarding fail;

  InternalOnboardingController(CompleteOnboarding complete, FailOnboarding fail) {
    this.complete = complete;
    this.fail = fail;
  }

  /** Texts, not enums, so that a number is never read as the position of a value. */
  record CompleteRequest(String firstAdministratorMembershipId, String initialStatus) {}

  record FailRequest(String failedStep) {}

  @PostMapping("/complete-onboarding")
  TenantResponse complete(
      @PathVariable String tenantId, @RequestAttribute(JwtAuthFilter.CALLER) Caller caller,
      @RequestBody CompleteRequest body) {
    return TenantResponse.of(complete.execute(new CompleteOnboarding.Command(
        tenantId, body.firstAdministratorMembershipId(), named("initial status", TenantStatus.class, body.initialStatus()),
        caller.subject(), CorrelationIdFilter.current())));
  }

  @PostMapping("/fail-onboarding")
  TenantResponse fail(@PathVariable String tenantId, @RequestBody FailRequest body) {
    return TenantResponse.of(fail.execute(new FailOnboarding.Command(tenantId, named("failed step", FailedStep.class, body.failedStep()))));
  }

  private static <E extends Enum<E>> E named(String what, Class<E> type, String value) {
    if (value == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidDataException("The " + what + " is not one of the allowed values");
    }
  }
}
