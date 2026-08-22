package eu.wohlben.qits.configuration.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.control.ConfigurationService;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.eventstream.control.EventFrame;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision, in isolation from the bus and the database. A {@link CapturingService}
 * stands in for the write seam and records what {@link SoftwareReleaseListener#onFrame} asks of it,
 * so the test is about the match rule alone — a {@code @QuarkusTest} would prove the same thing
 * behind a Quarkus boot it does not need.
 */
class SoftwareReleaseListenerTest {

  /** A {@link ConfigurationService} that writes nothing and remembers the one call it was handed. */
  private static final class CapturingService extends ConfigurationService {
    String application;
    String key;
    String value;
    String actor;
    int calls;

    @Override
    public ConfigurationEntry upsert(String application, String key, String value, String actor) {
      this.application = application;
      this.key = key;
      this.value = value;
      this.actor = actor;
      this.calls++;
      ConfigurationEntry entry = new ConfigurationEntry();
      entry.headRevision = 1;
      return entry;
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
        "evt-1", "SoftwareRelease", Instant.parse("2026-08-22T10:00:00Z"), payload, null, null);
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

    assertEquals(1, service.calls, "exactly one entry is written");
    assertEquals("qits-projects", service.application);
    assertEquals("env.QITS_PROJECTS_AGENT_IMAGE_VERSION", service.key);
    assertEquals("2026.822.101500", service.value);
    assertEquals("qits-configuration/software-release-listener", service.actor);
  }

  @Test
  void workspaceImageReleaseWritesTheWorkspacePin() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/workspace", "2026.822.101500");

    assertTrue(listener.selects(frame), "the workspace docker image must be selected");
    listener.onFrame(frame);

    assertEquals(1, service.calls, "exactly one entry is written");
    assertEquals("qits-workspaces", service.application);
    assertEquals("env.QITS_WORKSPACE_IMAGE_VERSION", service.key);
    assertEquals("2026.822.101500", service.value);
    assertEquals("qits-configuration/software-release-listener", service.actor);
  }

  @Test
  void aDifferentImageNameWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    EventFrame frame = frameFor("docker", "qits/qits-stt", "2026.822.101500");

    assertFalse(listener.selects(frame), "another image must not be selected");
    listener.onFrame(frame);

    assertEquals(0, service.calls, "no entry is written for another image");
    assertNull(service.value);
  }

  @Test
  void aNonDockerPackageTypeWritesNothing() {
    CapturingService service = new CapturingService();
    SoftwareReleaseListener listener = listenerWith(service);
    // Same name, wrong type: a maven artifact that happens to share the coordinate must not match.
    EventFrame frame = frameFor("maven", "qits/project-agent", "2026.822.101500");

    assertFalse(listener.selects(frame), "a non-docker type must not be selected");
    listener.onFrame(frame);

    assertEquals(0, service.calls, "no entry is written for a non-docker release");
  }
}
