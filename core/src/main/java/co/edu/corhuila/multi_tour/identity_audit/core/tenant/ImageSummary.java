package co.edu.corhuila.multi_tour.identity_audit.core.tenant;

/** What the public profile needs to say of a file: where it is and a hash to tell when it changed, never its bytes. */
public record ImageSummary(ImageSlot slot, int position, String sha256) {}
