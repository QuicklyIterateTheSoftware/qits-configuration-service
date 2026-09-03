package eu.wohlben.qits.configuration.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision, in isolation from the bus and the database. A {@link CapturingService}
 * stands in for the write seam and records what {@link SoftwareReleaseListener#onFrame} asks of it,
 * so the test is about the match rule alone — a {@code @QuarkusTest} would prove the same thing
 * behind a Quarkus boot it does not need.
 */
class SoftwareReleaseListenerTest {

  /** One {@code upsert} as the seam received it. */
  private record Write(String application, String key, String value, String actor) {}

  /**
   * A {@link ConfigurationService} that writes nothing and remembers every call it was handed.
   *
   * <p>Every call, not the last one: an image with two consumers is one frame and two upserts, and a
   * stand-in holding only the newest would let a listener that wrote one of them pass.
   */
  private static final class CapturingService extends ConfigurationService {
    final List<Write> writes = new ArrayList<>();

    @Override
    public ConfigurationEntry upsert(String application, String key, String value, String actor) {
      writes.add(new Write(application, key, value, actor));
      ConfigurationEntry entry = new ConfigurationEntry();
      entry.headRevision = 1;
      return entry;
    }

    /** The single write this frame was expected to make, failing the test when it made several. */
    Write only() {
      assertEquals(1, writes.size(), "exactly one entry is written");
      return writes.get(0);
    }

    /** The write that landed on an application, or null — the assertion for a fan-out frame. */
    Write on(String application) {
      return writes.stream().filter(w -> w.application().equals(application)).findFirst().orElse(null);
    }
  }

  private static EventFrame frameFor(String packageType, String packageName, String version) {
    String payload =
        "{\"repository\":\"qits-projects\",\"version\":\""
            + version
            + "\",\"packageType\":\""
            + packageType
            + "\",\"packageName\":\""
            + packageName
            + "\"}";
    return new EventFrame(
        "evt-1", "SoftwareRelease", Instant.parse("2026-08-22T10:00:00Z"), payload, null, null, null);
  }

  private SoftwareReleaseListener listenerWith(CapturingService service) {
    SoftwareReleaseListener listener = new SoftwareReleaseListener();
    listener.configuration = service;
    return listener;
  }

  @Test
  void projectAgentImageReleaseWritesThePin() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/project-agent", "2026.822.101500");

    assertTrue(listener.selects(frame), "the project-agent docker image must be selected");
    listener.onFrame(frame);

    Write write = service.only();
    assertEquals("qits-projects", write.application());
    assertEquals("env.QITS_PROJECTS_AGENT_IMAGE_VERSION", write.key());
    assertEquals("2026.822.101500", write.value());
    assertEquals("qits-configuration/software-release-listener", write.actor());
  }

  /**
   * The workspace image is the one with two consumers: qits-workspaces starts a workspace from it and
   * qits-projects starts a refinement container from it. So one release is <b>two</b> entries, on two
   * applications, under the env key each of them reads — and the second of those replaced
   * qits-projects-service's {@code ci-event-upstream-workspace-daemon.yml}, which used to carry the
   * same follow by rewriting a property and releasing that service.
   */
  @Test
  void workspaceImageReleaseWritesBothPins() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace", "2026.822.101500");

    assertTrue(listener.selects(frame), "the workspace docker image must be selected");
    listener.onFrame(frame);

    assertEquals(2, service.writes.size(), "the workspace image moves two pins, not one");

    Write workspaces = service.on("qits-workspaces");
    assertNotNull(workspaces, "the application that starts a workspace must be pinned");
    assertEquals("env.QITS_WORKSPACE_IMAGE_VERSION", workspaces.key());
    assertEquals("2026.822.101500", workspaces.value());
    assertEquals("qits-configuration/software-release-listener", workspaces.actor());

    Write projects = service.on("qits-projects");
    assertNotNull(projects, "the application that starts a refinement container must be pinned too");
    // The env override of qits.projects.refinement-image-version, which
    // refinementhost/RefinementContainerFactory reads to compose the image it starts.
    assertEquals("env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", projects.key());
    assertEquals("2026.822.101500", projects.value());
    assertEquals("qits-configuration/software-release-listener", projects.actor());
  }

  /**
   * The editor image lands on the same application as the workspace image, under a key of its own —
   * and its name opens with the workspace image's, so this is also the assertion that the match is a
   * whole-name lookup rather than a prefix. The single write is the sharper half of that now that the
   * workspace image writes two: a prefix match would give the editor release the workspace's pins.
   */
  @Test
  void workspaceEditorImageReleaseWritesTheEditorPin() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace-editor", "2026.822.101500");

    assertTrue(listener.selects(frame), "the workspace-editor docker image must be selected");
    listener.onFrame(frame);

    Write write = service.only();
    assertEquals("qits-workspaces", write.application());
    assertEquals("env.QITS_EDITOR_IMAGE_VERSION", write.key());
    assertEquals("2026.822.101500", write.value());
    assertEquals("qits-configuration/software-release-listener", write.actor());
  }

  @Test
  void aDifferentImageNameWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/qits-stt", "2026.822.101500");

    assertFalse(listener.selects(frame), "another image must not be selected");
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes, "no entry is written for another image");
  }

  @Test
  void aNonDockerPackageTypeWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    // Same name, wrong type: a maven artifact that happens to share the coordinate must not match.
    EventFrame frame = frameFor("maven", "qits/project-agent", "2026.822.101500");

    assertFalse(listener.selects(frame), "a non-docker type must not be selected");
    listener.onFrame(frame);

    assertEquals(List.of(), service.writes, "no entry is written for a non-docker release");
  }
}
