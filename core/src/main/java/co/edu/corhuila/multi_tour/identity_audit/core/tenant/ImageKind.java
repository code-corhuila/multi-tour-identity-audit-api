package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/**
 * The three kinds of file a tenant may keep as its logo or commercial image. The kind is proved by the first bytes of the
 * file and by nothing the caller says: the bytes are served back to everyone, so they must be what they claim to be.
 * SVG is not one of them on purpose, because it can carry scripts.
 */
public enum ImageKind {
  PNG("image/png"),
  JPEG("image/jpeg"),
  WEBP("image/webp");

  private final String contentType;

  ImageKind(String contentType) {
    this.contentType = contentType;
  }

  public String contentType() {
    return contentType;
  }

  /** The kind of these bytes, or a refusal naming the field {@code data}. */
  public static ImageKind detect(byte[] bytes) {
    if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return PNG;
    }
    if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
      return JPEG;
    }
    if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
      return WEBP;
    }
    throw new InvalidDataException("data", "The file must be a PNG, a JPEG or a WebP image");
  }

  private static boolean startsWith(byte[] bytes, int from, int... expected) {
    if (bytes.length < from + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((bytes[from + i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }
}
