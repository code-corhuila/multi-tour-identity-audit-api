package co.edu.corhuila.multi_tour.identity_audit.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The domain of the service depends on no framework (norm 5.3.1): this module declares none, so a
 * {@code @Service} or a {@code @Document} in it does not compile. The test fails the day a dependency
 * brings Spring or the MongoDB driver onto the classpath of the module.
 */
class CoreHasNoFrameworkTest {

  @Test
  void D_core_has_no_spring_on_its_classpath() {
    assertThatThrownBy(() -> Class.forName("org.springframework.core.SpringVersion"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void D_core_has_no_mongodb_driver_or_persistence_annotations_on_its_classpath() {
    for (String type :
        new String[] {"com.mongodb.client.MongoClient", "org.bson.Document", "jakarta.persistence.Entity"}) {
      assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
    }
  }
}
