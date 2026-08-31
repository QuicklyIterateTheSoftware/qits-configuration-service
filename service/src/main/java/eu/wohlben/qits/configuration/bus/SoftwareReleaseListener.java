package eu.wohlben.qits.configuration.bus;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The bus end of the platform's image pins: consumes qits-ci's {@code SoftwareRelease} and, when the
 * released package is a docker image this service pins, writes its version into this service's own
 * store as an env entry on the owning application.
 *
 * <p>The set of pins is a small immutable map from docker {@code packageName} to a {@link Pin} —
 * the application the entry lands on and the env-var key the deployer expands. Three images today:
 *
 * <ul>
 *   <li>{@code qits/project-agent} &rarr; {@code env.QITS_PROJECTS_AGENT_IMAGE_VERSION} on {@code
 *       qits-projects}
 *   <li>{@code qits/workspace} &rarr; {@code env.QITS_WORKSPACE_IMAGE_VERSION} on {@code
 *       qits-workspaces}
 *   <li>{@code qits/workspace-editor} &rarr; {@code env.QITS_EDITOR_IMAGE_VERSION} on {@code
 *       qits-workspaces}
 * </ul>
 *
 * <p>Two of them land on the same application, which the map already allows: a pin is keyed by the
 * released image, and nothing about the write assumes one entry per application.
 *
 * <p>Adding a fourth is one more entry in {@link #PINS}; the match, the write, and the failure rules
 * below all read from the map, so nothing else changes.
 *
 * <p><b>Why the bus rather than a call.</b> An owning application starts its image per container and
 * needs to start the version that was just released — a fact only qits-ci knows, the moment its
 * release pipeline goes green. The alternative, qits-ci reaching into this service on every release,
 * would make a config write a synchronous leg of a release and lose it whenever this service was
 * mid-cutover. A {@link QitsDurableEventListener} closes that: the release is caught up after a
 * restart, and the write happens exactly once per release the platform can be sure of.
 *
 * <p>It adapts inward the way the platform's other consumers do — an {@link EventFrame} is decoded
 * into a local {@link SoftwareReleasePayload} record by the eventstream lib's {@link CanonicalJson},
 * so this module depends on no qits-ci module at all. The record is registered for native reflection
 * in {@code bus/EventWireReflection}, because {@code CanonicalJson} binds through its own
 * ObjectMapper the build step cannot see.
 *
 * <h2>The match, and why by the wire strings</h2>
 *
 * <p>A {@code SoftwareRelease} is acted on only when its {@code packageType} is {@code "docker"} and
 * its {@code packageName} is a key of {@link #PINS}. Both are compared as the literal wire values
 * qits-ci publishes — the {@code SoftwareRelease} javadoc fixes {@code packageType}'s vocabulary as
 * {@code npm/maven/docker/daemon} — rather than through qits-ci's {@code CiArtifact.Type} enum, which
 * lives in a module this service does not (and should not) depend on. The name travels unqualified,
 * so it is matched unqualified.
 *
 * <p>The name match is a map lookup, which is to say <b>whole and exact, never a prefix</b>. That is
 * load-bearing now that {@code qits/workspace} and {@code qits/workspace-editor} share an opening:
 * they are two images with two pins, and a release of either must move its own key and only its own.
 *
 * <h2>Last-writer-wins, and why it is safe here</h2>
 *
 * <p>The effect is an idempotent upsert keyed by the event's own fact — the released version — so it
 * needs none of the tip-checking a ladder does. {@link ConfigurationService#upsert} writes no
 * revision when the value is already what it would set, so a redelivery of the same release, live or
 * caught up, changes nothing. The one residual is a catch-up frame arriving after a newer live one
 * could momentarily rewrite an older version; the next release corrects it, and the platform's own
 * ordering makes it rare. Writing through {@code upsert} keeps the revision and audit trail exactly
 * as an operator's edit would.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything the write raises out of its own database work. A
 * store that is down is a condition rather than a verdict, so the claim rolls back and the release
 * stays owed for the next sweep.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse, and one that matches
 * a pinned image but names no version. Neither can succeed on a later offer — the same bytes fail
 * identically every time — and a throw would hold this consumer's watermark behind one bad event
 * forever.
 */
@ApplicationScoped
public class SoftwareReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(SoftwareReleaseListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} this listener is caught up by. A stable storage key, not a description — its
   * value long predates the second image, and renaming it would reset the watermark and re-consume
   * every past release. It survives a rename of this class, and it is never handed to a listener that
   * means something else.
   */
  static final String CONSUMER_ID = "configuration.project-agent-image";

  /** The one event name this listener wants — {@code SoftwareRelease}'s signature. */
  static final String SOFTWARE_RELEASE = "SoftwareRelease";

  /** The {@code packageType} value a docker image release carries, as the wire spells it. */
  static final String DOCKER_TYPE = "docker";

  /** Who the revision records as the writer, the way {@code ConfigurationController.actor()} does. */
  static final String ACTOR = "qits-configuration/software-release-listener";

  /** Where a released image version lands: the application, then the env-var key the deployer expands. */
  record Pin(String application, String key) {}

  /**
   * The docker images this service pins, keyed by the unqualified {@code packageName} qits-ci
   * publishes. Add an image by adding an entry here.
   */
  static final Map<String, Pin> PINS =
      Map.of(
          "qits/project-agent", new Pin("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
          "qits/workspace", new Pin("qits-workspaces", "env.QITS_WORKSPACE_IMAGE_VERSION"),
          "qits/workspace-editor", new Pin("qits-workspaces", "env.QITS_EDITOR_IMAGE_VERSION"));

  /**
   * The {@code SoftwareRelease} payload wire fields this listener reads, as a local record bound by
   * {@link CanonicalJson}. Only the four the match and the pin need — {@code occurredAt} is on the
   * envelope, not the payload, so it is not here. Public so {@code bus/EventWireReflection} and the
   * test can name it; a copy of qits-ci's wire shape rather than a dependency on its module.
   */
  public record SoftwareReleasePayload(
      String repository, String version, String packageType, String packageName) {}

  @Inject ConfigurationService configuration;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SOFTWARE_RELEASE);
  }

  /**
   * Whether this release pins one of our images, decided from the payload alone — a pure read, which
   * is what the seam asks of a predicate, and the reason a {@code SoftwareRelease} for anything else
   * leaves no claim row behind it.
   *
   * <p>An unreadable payload answers <b>no</b> rather than throwing: the seam keeps offering an event
   * whose predicate throws, which is wrong for one that will read the same bytes and fail
   * identically forever.
   */
  @Override
  public boolean selects(EventFrame frame) {
    return matched(frame) != null;
  }

  /**
   * Writes the released version through the service's own write seam, so the entry carries a revision
   * and an author. Runs inside the claiming transaction; the write opens its own (retried) one, which
   * is safe because the upsert is idempotent — see the class javadoc.
   */
  @Override
  public void onFrame(EventFrame frame) {
    Matched matched = matched(frame);
    if (matched == null) {
      // selects() said yes a moment ago on the same immutable frame, so this is unreachable for a
      // match; for a non-selecting frame the funnel never calls onFrame. Cheaper than an assertion.
      return;
    }
    Pin pin = matched.pin();
    ConfigurationEntry entry =
        configuration.upsert(pin.application(), pin.key(), matched.version(), ACTOR);
    LOG.infof(
        "SoftwareRelease %s pinned %s=%s (application %s, revision %d)",
        frame.id(), pin.key(), matched.version(), pin.application(), entry.headRevision);
  }

  /** A frame that matched a pin: the pin it hit and the version it carries. */
  private record Matched(Pin pin, String version) {}

  /**
   * The pin and version this frame releases, or null when it releases something we do not pin or
   * cannot be read at all. Asked twice per event — once by {@link #selects}, once by {@link
   * #onFrame} — because a predicate the seam calls separately cannot hand state forward, and one
   * more decode of an already-in-memory string is cheaper than a per-frame cache that could disagree
   * with itself.
   */
  private static Matched matched(EventFrame frame) {
    SoftwareReleasePayload p;
    try {
      p = CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "SoftwareRelease %s carried an unreadable payload: %s", frame.id(), unreadable.toString());
      return null;
    }
    if (!DOCKER_TYPE.equals(p.packageType())) {
      return null;
    }
    Pin pin = PINS.get(p.packageName());
    if (pin == null) {
      return null;
    }
    String version = p.version();
    if (version == null || version.isBlank()) {
      // Poison: the image matched but there is nothing to pin, and the same bytes will match-and-fail
      // on every later offer. Settle it with a WARN rather than wedge the watermark behind it.
      LOG.warnf(
          "SoftwareRelease %s is the %s image but names no version; nothing to pin",
          frame.id(), p.packageName());
      return null;
    }
    return new Matched(pin, version);
  }
}
