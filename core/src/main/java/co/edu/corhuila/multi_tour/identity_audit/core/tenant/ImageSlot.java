package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** What a file is for: the one logo of a tenant, or one of its (at most five) commercial images. */
public enum ImageSlot {
  LOGO(256 * 1024, 1),
  IMAGE(1024 * 1024, 5);

  private final int maxBytes;
  private final int places;

  ImageSlot(int maxBytes, int places) {
    this.maxBytes = maxBytes;
    this.places = places;
  }

  /** The largest file, counted on the file and not on its encoding. */
  public int maxBytes() {
    return maxBytes;
  }

  /** A position outside the places of the slot is a refusal naming the field {@code position}. */
  public void requirePosition(int position) {
    if (position < 0 || position >= places) {
      throw new InvalidDataException("position", "The position must be from 0 to " + (places - 1));
    }
  }
}
