package co.edu.corhuila.multi_tour.identity_audit.adapters.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The public key of the security microservice, from {@code JWT_PUBLIC_KEY} or {@code JWT_PUBLIC_KEY_FILE}. */
@Configuration
class PublicKeyConfig {

  @Bean
  RSAPublicKey jwtPublicKey(
      @Value("${JWT_PUBLIC_KEY:}") String inline, @Value("${JWT_PUBLIC_KEY_FILE:}") String file)
      throws IOException, GeneralSecurityException {
    String pem = !inline.isBlank() ? inline : file.isBlank() ? "" : Files.readString(Path.of(file));
    if (pem.isBlank()) {
      throw new IllegalStateException("JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE is required");
    }
    // a key in an environment variable travels on one line, with the line breaks written as \n
    String base64 =
        pem.replace("\\n", "\n")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    return (RSAPublicKey)
        KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
  }
}
