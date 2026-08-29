package eu.wohlben.qits.configuration.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.configuration.stories.bootstrap.ConfigurationImportIT;
import eu.wohlben.qits.configuration.stories.deployment.DeploymentConfigurationIT;
import eu.wohlben.qits.configuration.stories.operator.OperatorEditIT;
import eu.wohlben.qits.configuration.stories.support.StoryEventBus;
import eu.wohlben.qits.configuration.stories.support.StoryIdentities;
import eu.wohlben.qits.configuration.stories.support.StoryPlatform;
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
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>Who may read and write the platform's configuration</b> — and none of it provable anywhere else
 * in this repository.
 *
 * <p>That last point is the whole reason these are integration tests. Inside a {@code @QuarkusTest}
 * qits-auth-core's {@code %test} dev user holds all four platform roles, so every door in this
 * service is open to a plain {@code given()} and a refusal cannot be observed at all. A launched
 * artifact runs in {@code NORMAL} mode with no dev user and the OIDC tenant on, which is the first
 * moment "no credential" and "the wrong one" mean anything.
 *
 * <p>The stake is stated in this repository's own notes and is not rhetorical: <b>this service is
 * credential-bearing infrastructure.</b> Its database is what every deployment's environment is read
 * from, so its write surface carries the sensitivity of the {@code qits-deployments-config} volume
 * it replaced. There is no anonymous route here and there must never be one.
 *
 * <p>Three stories, and the third is not a refusal at all:
 *
 * <ol>
 *   <li>an <b>unauthenticated</b> caller reaches nothing — 401 rather than 403, because the
 *       credential never became an identity and there is no caller to have been forbidden;
 *   <li>a <b>signed-in</b> person without a platform role is 403 on the same routes: they
 *       authenticated, and then were not allowed;
 *   <li>and the two identity tracks — a person's headers and a machine's bearer — open the
 *       <b>same</b> door, which is why no route here is machine-only. The reads are pulled by the
 *       deployer per deployment AND read by an operator; the writes are made by an operator AND by
 *       the bootstrap's import. A guard for either one would lock the other out.
 * </ol>
 *
 * <p>Each refusal also makes a claim a presence check cannot: nothing left this process. A refusal
 * is decided at the door, so no store is read on behalf of a caller that is about to be refused, and
 * nothing is written by one.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AccessRefusalIT {

  static final String CATEGORY = "authorization";

  static final String ANONYMOUS_SLUG = "an-unauthenticated-caller-reaches-nothing-at-all";
  static final String FORBIDDEN_SLUG = "a-signed-in-session-without-a-platform-role-is-forbidden";
  static final String BOTH_TRACKS_SLUG = "a-person-and-a-machine-open-the-same-door";

  static final String ANONYMOUS = "an unauthenticated caller";

  static final String READER = "a signed-in reader";

  static final String OPERATOR = "an operator";

  static final String DEPLOYER = "qits-platform-deployments";

  /** The application the refused writes name — and which must therefore never come to exist. */
  static final String APPLICATION = "story-refusal-app";

  static final String KEY = "env.QITS_INTRUDER";

  /** Kept so {@code @AfterAll} can assert the deployer's bearer is not in the published bundle. */
  private static String deployerBearer;

  /** The inbound tap, once. The framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * Provision first, floor second, both idempotent per JVM — the fixture is what the anonymous read
   * is refused, and it has to exist for "refused" to mean anything more than "not found". In
   * {@code @BeforeEach} because {@link StoryPlatform} builds urls from {@code RestAssured.port},
   * which the Quarkus integration-test extension sets per test and clears back to {@code -1}.
   */
  @BeforeEach
  void provisionThenFloorTheEventLogRecording() {
    StoryPlatform.provision();
    StoryEventBus.install();
  }

  @UserStory(value = "An unauthenticated caller reaches nothing at all", category = CATEGORY)
  @UserStoryDescription(
      """
      Every route on this service is @RolesAllowed and there is no anonymous one — not the
      applications overview, not the deployer's resolved read, not the write. A caller with no
      credential is answered 401 and not 403: the request never became an identity, so there is
      nobody to have been forbidden. The write is refused at the door, before any of it happens,
      so the application the request named does not exist afterwards. The client served at this
      service's root is the one thing an anonymous browser does get, and that is a static bundle
      rather than this service's data.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class,
    OperatorEditIT.class
  })
  @Order(1)
  void anAnonymousCallerIsRefusedEverything(Interactions story) {
    NetworkCapture.actor(ANONYMOUS);

    given().when().get(StoryTarget.APPLICATIONS_PATH).then().statusCode(401);
    story.note("the overview of every configured application is not public").as("overview-refused");

    given()
        .when()
        .get(StoryTarget.resolvedPath(StoryPlatform.APPLICATION))
        .then()
        .statusCode(401);
    story
        .note("nor is the read a deployment is made from — this is a real, configured application")
        .as("resolved-refused");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("value", "owned"))
        .when()
        .put(StoryTarget.entryPath(APPLICATION, KEY))
        .then()
        .statusCode(401);
    story.note("and the write that would decide what a container starts with").as("write-refused");

    // Read back through the fixture's own client — the one no tap is attached to — so proving the
    // absence does not put an arrow in the diagram of a story about arrows that never happened.
    assertEquals(
        0L,
        StoryPlatform.headRevisionOf(APPLICATION),
        "a refused write must leave no revision, and no application either");
    assertNull(StoryPlatform.valueOf(APPLICATION, KEY), "and certainly no entry");
    story.note("the application the refused write named does not exist").as("nothing-written");
  }

  @UserStory(
      value = "A signed-in session without a platform role is forbidden",
      category = CATEGORY)
  @UserStoryDescription(
      """
      The platform edge asserts a logged-in person's roles, and holding a session is not holding
      qits:admin. A reader who is signed in is answered 403 rather than 401 on the same two routes
      — they authenticated, and then were not allowed — which is the distinction that tells
      somebody whether to log in again or to ask for a role. Nothing they asked for was read and
      nothing they sent was written: the decision is made at the door.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class,
    OperatorEditIT.class
  })
  @Order(2)
  void aSignedInReaderIsForbidden(Interactions story) {
    NetworkCapture.actor(READER);

    StoryIdentities.person(given(), "mallory", StoryIdentities.READER_ROLE)
        .when()
        .get(StoryTarget.APPLICATIONS_PATH)
        .then()
        .statusCode(403);
    story
        .note("a session carrying qits:user is a real identity, and it opens nothing here")
        .as("read-forbidden");

    StoryIdentities.person(given(), "mallory", StoryIdentities.READER_ROLE)
        .contentType(ContentType.JSON)
        .body(Map.of("value", "owned"))
        .when()
        .put(StoryTarget.entryPath(APPLICATION, KEY))
        .then()
        .statusCode(403);
    story.note("and the write even less").as("write-forbidden");

    assertEquals(
        0L, StoryPlatform.headRevisionOf(APPLICATION), "still nothing, and still no application");
    story.note("the store is exactly as it was before they asked").as("store-untouched");
  }

  @UserStory(value = "A person and a machine open the same door", category = CATEGORY)
  @UserStoryDescription(
      """
      Every route here names the same pair of roles — qits:admin for a person, through the edge's
      forward-auth headers, and qits:system for a machine, through a bearer validated against
      qits-platform-idp — and none of them is machine-only. That is a decision, not an oversight:
      the reads are pulled by the deployer once per deployment AND read by an operator in a
      browser, and the writes are made by an operator AND by the bootstrap's import. A machine-only
      guard on the reads would lock the operator out of what they had just written; one on the
      writes would lock them out of writing at all. So the same overview answers both, and the two
      arrows into this service differ only in who drew them.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class,
    OperatorEditIT.class
  })
  @Order(3)
  void bothIdentityTracksOpenTheSameDoor(Interactions story) {
    NetworkCapture.actor(OPERATOR);
    List<String> asPerson =
        StoryIdentities.person(given(), "dana")
            .when()
            .get(StoryTarget.APPLICATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications.application", String.class);
    assertTrue(
        asPerson.contains(StoryPlatform.APPLICATION),
        "an operator sees the applications this platform is configured with");
    story
        .note("an operator's session — two headers the edge asserted — opens the overview")
        .as("person-served");

    NetworkCapture.actor(DEPLOYER);
    deployerBearer = StoryIdentities.platformToken(DEPLOYER);
    List<String> asMachine =
        given()
            .header("Authorization", "Bearer " + deployerBearer)
            .when()
            .get(StoryTarget.APPLICATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications.application", String.class);
    assertEquals(asPerson, asMachine, "the same door, and the same answer behind it");
    story
        .note("the deployer's bearer opens the very same route, and reads the very same thing")
        .as("machine-served");
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the anonymous caller -------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, ANONYMOUS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "overview-refused");
    ReportAssertions.assertStepId(CATEGORY, ANONYMOUS_SLUG, "nothing-written");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.APPLICATIONS_PATH + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(StoryPlatform.APPLICATION) + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY,
        ANONYMOUS_SLUG,
        NetworkEdge.HTTP,
        ANONYMOUS,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(APPLICATION, KEY) + " -> 401");
    ReportAssertions.assertEdgeCount(CATEGORY, ANONYMOUS_SLUG, 3);
    // The claim only a negative can make: a refusal costs this process nothing. No store was read
    // on behalf of a caller about to be refused, and the fixture's own read of the aftermath went
    // through a client no tap is attached to, so it is not in here either.
    ReportAssertions.assertNoEdgesFrom(CATEGORY, ANONYMOUS_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, ANONYMOUS_SLUG, List.of(ANONYMOUS));

    // --- the signed-in reader -------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, FORBIDDEN_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, FORBIDDEN_SLUG, "read-forbidden");
    ReportAssertions.assertStepId(CATEGORY, FORBIDDEN_SLUG, "store-untouched");
    ReportAssertions.assertEdge(
        CATEGORY,
        FORBIDDEN_SLUG,
        NetworkEdge.HTTP,
        READER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.APPLICATIONS_PATH + " -> 403");
    ReportAssertions.assertEdge(
        CATEGORY,
        FORBIDDEN_SLUG,
        NetworkEdge.HTTP,
        READER,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(APPLICATION, KEY) + " -> 403");
    ReportAssertions.assertEdgeCount(CATEGORY, FORBIDDEN_SLUG, 2);
    ReportAssertions.assertNoEdgesFrom(CATEGORY, FORBIDDEN_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, FORBIDDEN_SLUG, List.of(READER));

    // --- both tracks ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, BOTH_TRACKS_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, BOTH_TRACKS_SLUG, "person-served");
    ReportAssertions.assertStepId(CATEGORY, BOTH_TRACKS_SLUG, "machine-served");
    // Two arrows with the same label and different initiators: the whole story, drawn.
    ReportAssertions.assertEdge(
        CATEGORY,
        BOTH_TRACKS_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.APPLICATIONS_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        BOTH_TRACKS_SLUG,
        NetworkEdge.HTTP,
        DEPLOYER,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.APPLICATIONS_PATH + " -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, BOTH_TRACKS_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, BOTH_TRACKS_SLUG, List.of(OPERATOR, DEPLOYER));
    ReportAssertions.assertNotLeaked(CATEGORY, BOTH_TRACKS_SLUG, deployerBearer);
  }
}
