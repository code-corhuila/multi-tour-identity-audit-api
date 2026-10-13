package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The two colors a company chooses for its channel, each {@code #} and six hexadecimal digits. Input is
 * case-insensitive and kept in capitals, so one color written in two ways is one value. Nothing else of the
 * layout is the company's to change.
 */
public record VisualIdentity(String primaryColor, String secondaryColor) {

  private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

  public VisualIdentity {
    primaryColor = color(primaryColor, "The primary color");
    secondaryColor = color(secondaryColor, "The secondary color");
  }

  /** {@code matches} must cover the whole text, and a line break at the end is not covered. */
  private static String color(String value, String what) {
    if (value == null || !COLOR.matcher(value).matches()) {
      throw new InvalidDataException(what + " is # and six hexadecimal digits");
    }
    return value.toUpperCase(Locale.ROOT);
  }
}