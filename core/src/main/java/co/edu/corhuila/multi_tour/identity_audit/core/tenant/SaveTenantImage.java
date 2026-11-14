package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import co.edu.corhuila.multi_tour.identity_audit.core.security.Caller;
import co.edu.corhuila.multi_tour.identity_audit.core.security.CallerNotAllowedException;
import co.edu.corhuila.multi_tour.identity_audit.core.security.Role;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The Tenant Administrator saves the logo or a commercial image of their own company. The tenant is the caller's and is
 * never named by the request. A file that is refused never reaches the store, so the previous one stays.
 */
public final class SaveTenantImage {

  private final TenantRepository tenants;
  private final TenantImages images;
  private final Clock clock;

  public SaveTenantImage(TenantRepository tenants, TenantImages images, Clock clock) {
    this.tenants = tenants;
    this.images = images;
    this.clock = clock;
  }

  /** @param data the file in strict base64; {@code contentType} must agree with what the bytes are */
  public Tenant execute(Caller caller, ImageSlot slot, int position, String contentType, String data) {
    if (!caller.roles().contains(Role.TENANT_ADMINISTRATOR) || caller.tenantId() == null) {
      throw new CallerNotAllowedException();
    }
    slot.requirePosition(position);
    byte[] bytes = decode(slot, data);
    ImageKind kind = ImageKind.detect(bytes);
    if (!kind.contentType().equals(contentType)) {
      throw new InvalidDataException("contentType", "The content type does not match the file");
    }
    String tenantId = caller.tenantId();
    Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException(tenantId));
    if (tenant.status() != TenantStatus.ACTIVE) {
      throw new BusinessRuleViolationException("A " + tenant.status() + " tenant cannot change its logo or its images");
    }
    images.save(new TenantImage(tenantId, slot, position, kind, bytes, sha256(bytes), caller.subject(), clock.instant()));
    return tenant;
  }

  private static byte[] decode(ImageSlot slot, String data) {
    if (data == null || data.isEmpty()) {
      throw new InvalidDataException("data", "The file is required");
    }
    // the padding is part of the text: the decoder of the JDK would accept it missing
    if (data.length() % 4 != 0) {
      throw new InvalidDataException("data", "The file must be written in base64");
    }
    // the largest encoding of a file within the limit: refuse a bigger text before decoding it
    if (data.length() > (slot.maxBytes() + 2) / 3 * 4) {
      throw new InvalidDataException("data", "The file is larger than " + slot.maxBytes() + " bytes");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(data);
    } catch (IllegalArgumentException notBase64) {
      throw new InvalidDataException("data", "The file must be written in base64");
    }
    if (bytes.length > slot.maxBytes()) {
      throw new InvalidDataException("data", "The file is larger than " + slot.maxBytes() + " bytes");
    }
    return bytes;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
