package eu.wohlben.qits.configuration.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE IMAGE PINS, and there is only this one list of them.
 *
 * <p>A pin says: when this docker image is released, its version becomes the value of this key on
 * this application — an ordinary configuration entry, which the deployer expands into an environment
 * variable and the application starts its next container from. Three images and four pins today:
 *
 * <ul>
 *   <li>{@code qits/project-agent} &rarr; {@code env.QITS_PROJECTS_AGENT_IMAGE_VERSION} on {@code
 *       qits-projects}
 *   <li>{@code qits/workspace} &rarr; {@code env.QITS_WORKSPACE_IMAGE_VERSION} on {@code
 *       qits-workspaces}, <b>and</b> {@code env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION} on {@code
 *       qits-projects}
 *   <li>{@code qits/workspace-editor} &rarr; {@code env.QITS_EDITOR_IMAGE_VERSION} on {@code
 *       qits-workspaces}
 * </ul>
 *
 * <p><b>Two shapes of sharing, and the list allows both.</b> Several images may land on one
 * application — the workspace and editor pins do — because a pin is keyed by the released image and
 * nothing about the write assumes one entry per application. And one image may land on several
 * applications: {@code qits/workspace} is the toolchain-plus-daemon image that qits-workspaces starts
 * a workspace from and qits-projects starts a refinement container from, so a single release of it
 * has to move two independent keys on two applications.
 *
 * <p><b>The key spelling is the env override of the property the consumer reads</b>, not a name
 * invented here. SmallRye maps {@code QITS_PROJECTS_REFINEMENT_IMAGE_VERSION} onto {@code
 * qits.projects.refinement-image-version} by its own uppercase-and-underscore rule and lets the env
 * win over the committed default, so a key that does not transcribe an existing property writes an
 * entry the consumer never reads — and says nothing about it at any log level.
 *
 * <h2>Why it is here rather than beside the listener that writes it</h2>
 *
 * <p>Two paths read this list and they must never disagree. {@code bus/SoftwareReleaseListener}
 * matches a {@code SoftwareRelease} against {@link #BY_IMAGE} and WRITES the version; {@code
 * api/ImagePinsController} walks {@link #ORDERED}, resolves each mapping against the current entries
 * and REPORTS what is pinned. A private copy in either of them would be a second opinion about what
 * this platform launches — the pin mechanism and the pin report have to be the same list or the
 * report is fiction. Adding a pin is one more {@link Pin} in {@link #AUTHORED}; both views derive
 * from it, and nothing else changes.
 *
 * <h2>What the report is for: qits-artifacts' garbage collection</h2>
 *
 * <p>qits-artifacts reads {@code GET /configuration/api/pins} as a <b>pin source</b> when it decides
 * which container images it may delete. A configured image version is one a container launch will
 * pull <i>cold</i> — the deployer expands the entry, the host has never seen the tag, and no access
 * timestamp on the registry says so, because nothing has accessed it yet. Age and last-access are
 * exactly the wrong evidence for this class of image, so the answer here is the evidence instead.
 *
 * <p>The other direction is just as load-bearing: an image OUTSIDE this list is not
 * launchable-by-configuration at all, so it needs no row and gets none. That is what keeps the answer
 * a short, checkable statement about what the platform starts rather than a listing of everything it
 * has ever built.
 */
public final class ImagePins {

  /** Where a released image version lands: the image, the application, then the env-var key. */
  public record Pin(String image, String application, String key) {}

  /**
   * The pins as they are written down, in whatever order reads best. Nothing depends on this order —
   * {@link #ORDERED} sorts it — so an entry goes wherever its explanation belongs.
   */
  private static final List<Pin> AUTHORED =
      List.of(
          new Pin("qits/project-agent", "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
          new Pin("qits/workspace", "qits-workspaces", "env.QITS_WORKSPACE_IMAGE_VERSION"),
          // qits-projects starts its refinement containers from the same image
          // (refinementhost/RefinementContainerFactory), so its release moves this key too —
          // formerly qits-projects-service's ci-event-upstream-workspace-daemon.yml.
          new Pin(
              "qits/workspace", "qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"),
          new Pin("qits/workspace-editor", "qits-workspaces", "env.QITS_EDITOR_IMAGE_VERSION"));

  /**
   * Every pin in the answer's order — image, then application, then key — sorted here rather than
   * trusted to how {@link #AUTHORED} happens to be typed. The report is read by a machine that
   * compares one run's answer with the last one's, so the order is part of the contract and must not
   * be a property of an editor's cursor.
   */
  public static final List<Pin> ORDERED =
      AUTHORED.stream()
          .sorted(
              Comparator.comparing(Pin::image)
                  .thenComparing(Pin::application)
                  .thenComparing(Pin::key))
          .toList();

  /**
   * The same pins keyed by the unqualified {@code packageName} qits-ci publishes, which is how a
   * release is matched: <b>whole and exact, never a prefix</b>. That is load-bearing now that {@code
   * qits/workspace} and {@code qits/workspace-editor} share an opening — they are two images with
   * pins of their own, and a prefix match would have a workspace release quietly writing the editor's
   * key too.
   */
  public static final Map<String, List<Pin>> BY_IMAGE = byImage();

  private ImagePins() {}

  private static Map<String, List<Pin>> byImage() {
    Map<String, List<Pin>> grouped = new LinkedHashMap<>();
    for (Pin pin : ORDERED) {
      grouped.computeIfAbsent(pin.image(), image -> new ArrayList<>()).add(pin);
    }
    grouped.replaceAll((image, pins) -> List.copyOf(pins));
    return Map.copyOf(grouped);
  }
}
