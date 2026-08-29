package eu.wohlben.qits.configuration.stories.release;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.configuration.stories.bootstrap.ConfigurationImportIT;
import eu.wohlben.qits.configuration.stories.deployment.DeploymentConfigurationIT;
import eu.wohlben.qits.configuration.stories.operator.OperatorEditIT;
import eu.wohlben.qits.configuration.stories.refusals.AccessRefusalIT;
import eu.wohlben.qits.configuration.stories.support.StoryEventBus;
import eu.wohlben.qits.configuration.stories.support.StoryIdentities;
import eu.wohlben.qits.configuration.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The one thing this service goes out and fetches</b> — and the only way a value gets into its
 * store without somebody typing it.
 *
 * <p>Two of the platform's applications start a container per unit of work: qits-workspaces runs the
 * workspace image, qits-projects runs the project-agent image. Each has to start the version that
 * was <b>just released</b>, which is a fact only qits-ci knows and only at the moment its release
 * pipeline goes green. The alternative — qits-ci reaching into this service on every release — would
 * make a configuration write a synchronous leg of a release and lose it whenever this service was
 * mid-cutover. So the release travels as a {@code SoftwareRelease} on the platform's durable event
 * log, and {@code bus/SoftwareReleaseListener} pages it forward into an ordinary entry with an
 * ordinary revision: {@code env.QITS_WORKSPACE_IMAGE_VERSION} on {@code qits-workspaces}, {@code
 * env.QITS_PROJECTS_AGENT_IMAGE_VERSION} on {@code qits-projects}. The next deployment reads it
 * through the same resolved read every other extra comes through.
 *
 * <p><b>The direction of the arrow is the point.</b> Nothing pushes into this service: the listener
 * is durable, so the catch-up sweep <i>pulls</i> the log forward from its own watermark — which is
 * what makes a release survive this service being down, restarted or replaced while it happened.
 * The edge in the diagram is therefore {@code qits-configuration -> qits-events}, and it is the only
 * outgoing HTTP arrow in this whole catalogue that is not the startup fetch of the idp's keys.
 *
 * <p><b>And one of the three frames is deliberately ignored.</b> A {@code SoftwareRelease} is acted
 * on only when its {@code packageType} is {@code docker} and its {@code packageName} is an image
 * this service pins. The maven release of the same repository, published moments later and carrying
 * a much higher version, must not touch the pin — a version this service wrote from a jar's release
 * would start containers on an image tag that does not exist. It is ordered <b>before</b> the
 * project-agent frame on purpose: when the second pin appears, the frame between them has provably
 * been offered and skipped.
 *
 * <p>The far side is {@code stories.support.StoryEventBus}, which serves the log's list route and
 * records what was read — see it for why an empty poll is not an arrow.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImageReleasePinIT {

  static final String CATEGORY = "release";

  static final String PINNED_SLUG = "a-released-image-becomes-what-the-next-container-starts-with";

  /** The reader of the pin, and the reason it is written at all. */
  static final String DEPLOYER = "qits-platform-deployments";

  /** The applications the two pinned images belong to. */
  static final String WORKSPACES = "qits-workspaces";

  static final String PROJECTS = "qits-projects";

  /** …and the env-var keys the deployer expands into their containers. */
  static final String WORKSPACE_IMAGE_KEY = "env.QITS_WORKSPACE_IMAGE_VERSION";

  static final String AGENT_IMAGE_KEY = "env.QITS_PROJECTS_AGENT_IMAGE_VERSION";

  /** The versions this story releases. Authored literals: a value is not a path and is not scrubbed. */
  static final String WORKSPACE_VERSION = "2026.829.110000";

  static final String AGENT_VERSION = "2026.829.111000";

  /** The version the maven release carries, and which must never reach a pin. */
  static final String MAVEN_VERSION = "9999.1.1";

  /** Who the listener records as the writer — a machine, and it says which one. */
  static final String LISTENER_ACTOR = "qits-configuration/software-release-listener";

  /** How long a pin may take to arrive. Generous: the sweep's cadence is two seconds. */
  private static final Duration PATIENCE = Duration.ofSeconds(60);

  /** Kept so {@code @AfterAll} can assert the deployer's bearer is not in the published bundle. */
  private static String deployerBearer;

  /** The inbound tap, once. The framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * The far-side floor. Everything the catch-up sweep polled while the process was booting — and
   * everything it polled during the four story classes before this one — is below it, which is what
   * lets this story's own arrow be the one that carried a release.
   */
  @BeforeEach
  void floorTheEventLogRecording() {
    StoryEventBus.install();
  }

  @UserStory(
      value = "A released image becomes what the next container starts with",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-ci finishes a release of the workspace image and announces it on the platform's event
      log. qits-configuration is a durable consumer of that log: its catch-up sweep pages the log
      forward from its own watermark, finds the release, and writes the version into its own store
      as env.QITS_WORKSPACE_IMAGE_VERSION on qits-workspaces — an ordinary entry, with an ordinary
      revision, attributed to the listener that wrote it. The next deployment of qits-workspaces
      reads it through the same resolved read every other extra comes through, and starts its
      containers on the image that was just released.

      Three releases arrive together and only two are pins: the maven release of the same
      repository carries a far higher version and is left alone, because a pin is keyed on the
      docker package name and nothing else. Pulling rather than being pushed is what makes this
      survive: a release announced while this service was restarting is still read back the next
      time it sweeps.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class,
    OperatorEditIT.class,
    AccessRefusalIT.class
  })
  @Order(1)
  void aReleasedImageIsPinnedForTheNextDeployment(Interactions story) {
    NetworkCapture.actor(DEPLOYER);
    deployerBearer = StoryIdentities.platformToken(DEPLOYER);

    StoryEventBus.arm(
        List.of(
            StoryEventBus.softwareRelease(
                "release-workspace-image",
                "2026-08-29T11:00:00Z",
                WORKSPACES,
                WORKSPACE_VERSION,
                "docker",
                "qits/workspace"),
            // Between the two pins on purpose: when the second one lands, this one has provably
            // been offered to the listener and left alone.
            StoryEventBus.softwareRelease(
                "release-workspace-maven-jar",
                "2026-08-29T11:05:00Z",
                WORKSPACES,
                MAVEN_VERSION,
                "maven",
                "qits/workspace"),
            StoryEventBus.softwareRelease(
                "release-project-agent-image",
                "2026-08-29T11:10:00Z",
                PROJECTS,
                AGENT_VERSION,
                "docker",
                "qits/project-agent")));
    story
        .note("qits-ci announces three releases: two docker images and one jar of the same repository")
        .as("releases-announced");

    assertEquals(
        WORKSPACE_VERSION,
        awaitPin(WORKSPACES, WORKSPACE_IMAGE_KEY),
        "the workspace image's version must reach the application that starts it");
    story
        .note("the workspace image's version is now part of what a qits-workspaces container starts with")
        .as("workspace-image-pinned");

    assertEquals(
        AGENT_VERSION,
        awaitPin(PROJECTS, AGENT_IMAGE_KEY),
        "and the project-agent's, on the application that starts that one");
    story
        .note("so is the project agent's, on its own application — the pins are a map, not a special case")
        .as("agent-image-pinned");

    // The maven frame sat between the two, so it has been offered and skipped by now. This is what
    // says so: the pin is still the DOCKER version, not the jar's.
    assertEquals(
        WORKSPACE_VERSION,
        pinOf(WORKSPACES, WORKSPACE_IMAGE_KEY),
        "a maven release of the same repository must never move an image pin");
    story
        .note("the jar release of the same repository moved nothing: a pin is keyed on the image")
        .as("maven-release-ignored");

    List<Map<String, Object>> revisions =
        StoryIdentities.platformService(given(), DEPLOYER)
            .when()
            .get(StoryTarget.historyPath(WORKSPACES))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions");
    Map<String, Object> newest = revisions.stream().findFirst().orElseGet(() -> fail("no history"));
    assertEquals(WORKSPACE_IMAGE_KEY, newest.get("key"));
    assertEquals(WORKSPACE_VERSION, newest.get("value"));
    assertEquals(
        LISTENER_ACTOR,
        newest.get("updatedBy"),
        "the write is attributed like any other — to the listener, by name");
    story
        .note("the history records the pin as a revision, attributed to the listener that wrote it")
        .as("pin-attributed");
  }

  /**
   * The resolved read, repeated until the pin is there.
   *
   * <p>Every poll is the same request with the same answer status, so the tap draws exactly one
   * arrow for all of them — an edge is a dependency, and asking the same question twice does not
   * make two of them. Waiting is honest here rather than a workaround: the sweep is a timer, and the
   * story's claim is that the release arrives, not that it arrives instantly.
   */
  private static String awaitPin(String application, String key) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    String value = null;
    while (System.nanoTime() < deadline) {
      value = pinOf(application, key);
      if (value != null) {
        return value;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertNotNull(
        value,
        "the release never reached " + application + "." + key + " — the catch-up sweep found nothing");
    return value;
  }

  /** One pinned value as the deployer reads it, or null while the release has not arrived. */
  private static String pinOf(String application, String key) {
    JsonPath resolved =
        given()
            .header("Authorization", "Bearer " + deployerBearer)
            .when()
            .get(StoryTarget.resolvedPath(application))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    Map<String, String> properties = resolved.getMap("properties");
    return properties.get("qits.platform.deployments.extras." + application + "." + key);
  }

  @AfterAll
  static void theStoryReportIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, PINNED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "releases-announced");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "workspace-image-pinned");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "agent-image-pinned");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "maven-release-ignored");
    ReportAssertions.assertStepId(CATEGORY, PINNED_SLUG, "pin-attributed");

    // THE ONE OUTGOING ARROW. This service pages the platform's event log forward from its own
    // watermark; it is not called by qits-ci and it calls qits-ci about nothing.
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryEventBus.SERVICE_NAME,
        StoryEventBus.CATCHUP_LABEL);
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(WORKSPACES) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(PROJECTS) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        PINNED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.historyPath(WORKSPACES) + " -> 200");
    // Four arrows and no fifth, however many times the story polled: the two reads it waited on,
    // the history it checked, and the one page of the log that carried the releases.
    ReportAssertions.assertEdgeCount(CATEGORY, PINNED_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, PINNED_SLUG, List.of(DEPLOYER, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, PINNED_SLUG, deployerBearer);
  }
}
