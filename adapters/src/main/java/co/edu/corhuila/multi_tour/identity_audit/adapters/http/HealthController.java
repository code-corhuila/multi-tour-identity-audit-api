package co.edu.corhuila.multi_tour.identity_audit.adapters.http;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
