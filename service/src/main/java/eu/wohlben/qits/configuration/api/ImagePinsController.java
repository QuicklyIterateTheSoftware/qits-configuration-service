package eu.wohlben.qits.configuration.api;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The configured container-image versions: what a container launch on this platform would pull.
 *
 * <p>One row per {@code control/ImagePins} mapping that has a stored version — the image, the
 * version, and the entry it was read from. It is a projection of entries a caller could read one at
 * a time through {@code /applications/{app}/entries}; what it adds is <b>the map</b>, which lives in
 * this service and nowhere else, so the caller does not have to know that {@code
 * env.QITS_EDITOR_IMAGE_VERSION} on {@code qits-workspaces} is a version of {@code
 * qits/workspace-editor}.
 *
 * <p><b>Who reads it, and why an access timestamp will not do.</b> qits-artifacts' garbage collector
 * takes this as a pin source when it decides which images it may delete. A configured version is one
 * a launch will pull <i>cold</i>: the deployer expands the entry into a container's environment, the
 * host has never seen that tag, and the registry's own last-accessed record says nothing — because
 * nothing has accessed it yet. Deleting it is a workspace that will not start. So the pins are
 * evidence the collector holds against age, and an image outside the map is not
 * launchable-by-configuration and needs no row at all.
 *
 * <p>It sits at {@code /configuration/api/pins} rather than under {@code /applications} because it
 * carries its own application segments: the map names them, one per row.
 *
 * <p>Same pair of roles as every other route here — {@code qits:admin} for a person, {@code
 * qits:system} for the collector's bearer. There is no anonymous route in this service.
 */
@Path("/pins")
@Produces(MediaType.APPLICATION_JSON)
public class ImagePinsController {

  @Inject ConfigurationService configuration;

  /**
   * The answer: when it was assembled, and the pins.
   *
   * <p>{@code generatedAt} is the read's own moment, not a property of the store — the entries carry
   * their own {@code updatedAt} and this says nothing about them. It is here so a collector's
   * receipt can quote when it asked, which is the question anybody re-reading a deletion decision
   * has first.
   */
  public record ListPinsResponse(Instant generatedAt, List<ImagePinDto> pins) {}

  /**
   * Every configured image version, ordered by image, then application, then key.
   *
   * <p>A mapping with nothing stored is <b>omitted</b>: the image has never been released into this
   * environment, so there is no version to pin. An empty list is therefore an ordinary 200 and never
   * an error — a caller that read it as one would refuse to collect on a platform that has released
   * nothing.
   */
  @GET
  @Operation(summary = "The configured container-image versions, one row per pinned entry")
  @APIResponse(responseCode = "200", description = "The pins, possibly none")
  @RolesAllowed({"qits:admin", "qits:system"})
  public ListPinsResponse pins() {
    return new ListPinsResponse(Instant.now(), configuration.imagePins());
  }
}
