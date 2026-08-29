package eu.wohlben.qits.configuration.stories.deployment;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.configuration.stories.bootstrap.ConfigurationImportIT;
import eu.wohlben.qits.configuration.stories.support.StoryEventBus;
import eu.wohlben.qits.configuration.stories.support.StoryIdentities;
import eu.wohlben.qits.configuration.stories.support.StoryPlatform;
import eu.wohlben.qits.configuration.stories.support.StoryTarget;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The read that decides what a container starts with</b> — this service's reason to exist, from
 * the only angle that matters: the deployer's.
 *
 * <p>qits-platform-deployments performs exactly one {@code GET
 * /configuration/api/applications/<app>/resolved} per deployment, with its own machine identity, and
 * starts the container with what comes back. Two properties of that answer are the whole contract
 * and both are stories here:
 *
 * <ul>
 *   <li>the property <b>names are complete</b> — {@code
 *       qits.platform.deployments.extras.<app>.<key>} — so the consumer layers the map as a
 *       configuration source verbatim, with no prefix to re-assemble and no second place for the
 *       deployer's namespace to be written down. That namespace has moved twice already;
 *   <li>an application nobody has configured answers an <b>empty map at revision 0</b>, never a 404,
 *       because a deployer that read a 404 as an error would refuse every deployment of an
 *       application nobody has configured.
 * </ul>
 *
 * <p><b>And the negative is the interesting half.</b> Serving this read costs one query against this
 * service's own postgres and nothing else: no call to the idp (the bearer is judged on keys fetched
 * at startup — see {@code api.TokenValidationBootstrapIT}), no call to the catalogue that names the
 * application, no call to anybody. That is why {@code application} is a plain {@code varchar} with
 * no foreign key: configuration outlives the row that described the thing it configures. The store
 * is drawn as a <b>declared</b> edge, which is the framework's word for a dependency no tap can see;
 * {@code assertEdgeCount} beside it is what says there is no third.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeploymentConfigurationIT {

  static final String CATEGORY = "deployment";

  static final String RESOLVED_SLUG = "the-deployer-reads-what-a-container-starts-with";
  static final String UNCONFIGURED_SLUG = "an-application-nobody-configured-still-deploys";

  /** The one caller whose read decides something. */
  static final String DEPLOYER = "qits-platform-deployments";

  /** An application this service has never held a single entry for. */
  static final String UNCONFIGURED = "story-unconfigured-app";

  /** The store behind the answer — declared, because nothing on this side can observe it. */
  static final String STORE = "postgresql";

  static final String STORE_LABEL = "read the entries of one application";

  /** Kept so {@code @AfterAll} can assert the deployer's bearer is not in the published bundle. */
  private static String deployerBearer;

  /** The inbound tap, once. The framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * Provision first, floor second — and both in {@code @BeforeEach} rather than {@code @BeforeAll},
   * which is not a preference: {@link StoryPlatform} drives the API at {@code RestAssured.port}, and
   * the Quarkus integration-test extension sets that port in its <b>beforeEach</b> callback and
   * clears it back to {@code -1} in afterEach, so a {@code @BeforeAll} here would build a url
   * reading {@code http://localhost:-1}.
   *
   * <p>Both calls are idempotent per JVM, so the fixture is configured once, before the first story
   * of whichever class runs first — and the order matters for the same reason it does in every other
   * repository's stories: the floor is what keeps fixture traffic out of every diagram. Here the
   * fixture is invisible to the inbound tap anyway (it uses a client no filter is attached to), and
   * the far-side floor covers the event log, whose sweep ticks whether or not anybody is watching.
   */
  @BeforeEach
  void provisionThenFloorTheEventLogRecording() {
    StoryPlatform.provision();
    StoryEventBus.install();
  }

  @UserStory(value = "The deployer reads what a container starts with", category = CATEGORY)
  @UserStoryDescription(
      """
      Once per deployment, qits-platform-deployments asks this service for one application's
      resolved configuration and starts the container with the answer. The properties come back at
      their FULL names — qits.platform.deployments.extras.<app>.<key> — because the deployer layers
      the map into its own configuration verbatim; a bare-key answer would put the deployer's
      namespace in a second place, and that namespace has already moved twice. Beside them travels
      the head revision, which is what the deployer records to say which configuration this
      container was started with. Nothing is pushed: the deployer pulls, with its own identity, and
      this service reaches out to nobody at all to answer it.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, ConfigurationImportIT.class})
  @Order(1)
  void theDeployerResolvesAnApplication(Interactions story, Network network) {
    NetworkCapture.actor(DEPLOYER);
    deployerBearer = StoryIdentities.platformToken(DEPLOYER);

    JsonPath resolved =
        given()
            .header("Authorization", "Bearer " + deployerBearer)
            .when()
            .get(StoryTarget.resolvedPath(StoryPlatform.APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    Map<String, String> properties = resolved.getMap("properties");
    for (Map.Entry<String, String> entry : StoryPlatform.entries().entrySet()) {
      String name =
          "qits.platform.deployments.extras." + StoryPlatform.APPLICATION + "." + entry.getKey();
      assertEquals(
          entry.getValue(),
          properties.get(name),
          "the deployer reads " + name + " and layers it under exactly that name");
    }
    assertEquals(
        StoryPlatform.entries().size(),
        properties.size(),
        "and reads nothing it was not configured with");
    story
        .note(
            "one read answers the whole application: extra environment, a mount, a published port"
                + " and a network alias, each at its full property name")
        .as("configuration-resolved");

    assertTrue(
        resolved.getLong("headRevision") > 0,
        "the answer names the revision the deployment is being made with");
    story
        .note("beside them the head revision, which the deployer records against the deployment")
        .as("revision-recorded");

    // The one dependency no tap on this side can see. It is DECLARED rather than observed, which is
    // the framework's distinction between a claim and evidence — and it is the honest answer to
    // "what does this service call out to while it serves the platform's most load-bearing read".
    network.declare(NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
  }

  @UserStory(value = "An application nobody configured still deploys", category = CATEGORY)
  @UserStoryDescription(
      """
      Most applications on the platform carry no extras at all, and they are deployed constantly.
      So the resolved read of an application this service has never heard of is not an error: it is
      an empty map at revision zero — a complete and useful answer to "what extras does this
      application have". A 404 here would make the deployer treat "nothing configured" as a failure
      and refuse every deployment of every application nobody has customised, which is most of
      them.
      """)
  @UserflowRunsAfter({TokenValidationBootstrapIT.class, ConfigurationImportIT.class})
  @Order(2)
  void anUnconfiguredApplicationResolvesEmpty(Interactions story) {
    NetworkCapture.actor(DEPLOYER);

    JsonPath resolved =
        StoryIdentities.platformService(given(), DEPLOYER)
            .when()
            .get(StoryTarget.resolvedPath(UNCONFIGURED))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(0, resolved.getMap("properties").size(), "nothing is configured, so nothing comes back");
    assertEquals(
        0L,
        resolved.getLong("headRevision"),
        "revision zero is 'no history', and it is a number the deployer can record like any other");
    story
        .note("an application with no entries answers an empty map at revision zero, not a 404")
        .as("empty-not-missing");
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the deployer's read --------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, RESOLVED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, RESOLVED_SLUG, "configuration-resolved");
    ReportAssertions.assertStepId(CATEGORY, RESOLVED_SLUG, "revision-recorded");
    ReportAssertions.assertEdge(
        CATEGORY,
        RESOLVED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(StoryPlatform.APPLICATION) + " -> 200");
    ReportAssertions.assertDeclaredEdge(
        CATEGORY, RESOLVED_SLUG, NetworkEdge.JDBC, StoryTarget.SERVICE, STORE, STORE_LABEL);
    // TWO edges: the read in, and the store behind it. There is no third, and that is the claim —
    // the deployment of every application on this platform waits on this answer, so anything this
    // service consulted to produce it would be a dependency of every deployment.
    ReportAssertions.assertEdgeCount(CATEGORY, RESOLVED_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, RESOLVED_SLUG, List.of(DEPLOYER, StoryTarget.SERVICE));
    // In particular the idp is not asked. A bearer is validated against keys this process fetched
    // once, at startup; if it were re-fetched per request, qits-platform-idp would be on the
    // critical path of every deployment.
    ReportAssertions.assertNoEdgesTo(CATEGORY, RESOLVED_SLUG, MockIdp.SERVICE_NAME);
    ReportAssertions.assertNotLeaked(CATEGORY, RESOLVED_SLUG, deployerBearer);

    // --- the unconfigured application -----------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, UNCONFIGURED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, UNCONFIGURED_SLUG, "empty-not-missing");
    ReportAssertions.assertEdge(
        CATEGORY,
        UNCONFIGURED_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(UNCONFIGURED) + " -> 200");
    // ONE edge, and nothing left this process: an application with no rows is answered without a
    // lookup anywhere — not in the platform catalogue that would know the name, not in the log.
    ReportAssertions.assertEdgeCount(CATEGORY, UNCONFIGURED_SLUG, 1);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, UNCONFIGURED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, UNCONFIGURED_SLUG, List.of(DEPLOYER));
  }
}
