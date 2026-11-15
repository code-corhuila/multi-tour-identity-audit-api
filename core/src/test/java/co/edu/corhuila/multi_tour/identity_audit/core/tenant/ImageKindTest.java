package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The kind of a file is read from its first bytes and from nothing the caller says (RN-IDA-API-121). */
class ImageKindTest {

  static byte[] png(int size) {
    byte[] bytes = new byte[size];
    byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    return bytes;
  }

  static byte[] jpeg(int size) {
    byte[] bytes = new byte[size];
    byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    return bytes;
  }

  static byte[] webp(int size) {
    byte[] bytes = new byte[size];
    byte[] head = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
    return bytes;
  }

  @Test
  void D_the_kind_is_read_from_the_bytes() {
    assertThat(ImageKind.detect(png(64))).isEqualTo(ImageKind.PNG);
    assertThat(ImageKind.detect(jpeg(64))).isEqualTo(ImageKind.JPEG);
    assertThat(ImageKind.detect(webp(64))).isEqualTo(ImageKind.WEBP);
    assertThat(ImageKind.PNG.contentType()).isEqualTo("image/png");
    assertThat(ImageKind.JPEG.contentType()).isEqualTo("image/jpeg");
    assertThat(ImageKind.WEBP.contentType()).isEqualTo("image/webp");
  }

  @Test
  void D_a_file_that_is_not_one_of_the_three_kinds_is_refused_naming_the_field() {
    byte[][] refused = {
        new byte[0],
        new byte[] {(byte) 0x89, 'P', 'N', 'G'},
        new byte[] {(byte) 0xFF, (byte) 0xD8},
        java.util.Arrays.copyOf(webp(64), 11),
        "----RIFF----WEBP".getBytes(StandardCharsets.US_ASCII),
        "GIF89a-----------".getBytes(StandardCharsets.US_ASCII),
        "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
        "RIFF----WAVE".getBytes(StandardCharsets.US_ASCII),
        "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)};
    for (byte[] bytes : refused) {
      assertThatThrownBy(() -> ImageKind.detect(bytes))
          .isInstanceOfSatisfying(InvalidDataException.class, e -> assertThat(e.field()).isEqualTo("data"));
    }
  }
}
