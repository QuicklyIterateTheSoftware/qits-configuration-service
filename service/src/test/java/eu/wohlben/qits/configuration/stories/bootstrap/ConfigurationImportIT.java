package eu.wohlben.qits.configuration.stories.bootstrap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
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
import io.restassured.http.ContentType;
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
 * <b>How the platform's configuration got here</b> — the migration off qits-platform-deployments'
 * config volume, which is the reason this service exists at all.
 *
 * <p>Before it, a deployment's extra environment, mounts, published ports, groups and aliases lived
 * in a hand-edited properties file on the deployer's own volume, snapshotted at deployer boot. An
 * edit was inert until the deployer was forced to reload, and a live fix was reverted by the next
 * deploy. The file is still the artefact everybody holds, so the door that takes it takes it
 * <b>whole</b>: {@code text/plain}, comments, blank lines and the deployer's own unrelated keys
 * included, and it reports how many lines it ignored rather than refusing them. An endpoint that
 * made its callers filter the file first would have put a second parser of the deployer's format
 * into a shell script.
 *
 * <p>The three stories are the three things that door promises:
 *
 * <ol>
 *   <li>it takes the file as exported, writes what is its business and says what it ignored;
 *   <li>it is <b>idempotent</b>, which is what lets a bootstrap re-run it on every boot — the
 *       history stays a record of changes rather than of runs;
 *   <li>a malformed line refuses the <b>whole file</b>, because a half-applied import would be
 *       worse than a failed one: the operator would have to work out which half.
 * </ol>
 *
 * <p>The caller is a machine — {@code qits-cli-bootstrap} holds {@code qits:system} — and its {@code
 * sub} is what the revisions record. That is the same audit trail an operator's {@code X-Qits-User}
 * writes into; who changed a value is never a guess here.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConfigurationImportIT {

  static final String CATEGORY = "bootstrap";

  static final String IMPORTED_SLUG = "the-config-volume-s-file-is-imported-whole";
  static final String REPEATED_SLUG = "re-importing-the-same-file-writes-nothing-at-all";
  static final String REFUSED_SLUG = "one-malformed-line-refuses-the-whole-file";

  /** The machine that seeds a platform, and the {@code sub} its bearer carries. */
  static final String BOOTSTRAP = "qits-cli-bootstrap";

  /** The two applications this file configures. Literals — see {@link StoryTarget}. */
  static final String ALPHA = "story-import-alpha";

  static final String BETA = "story-import-beta";

  /** The application the refused file names, and which must therefore not exist afterwards. */
  static final String REFUSED = "story-import-refused";

  /**
   * The file as the deployer's volume holds it: a comment, four entries across two applications, a
   * blank line, and two of the deployer's own keys that are none of this service's business.
   *
   * <p>Nine lines, four of them this service's — which is exactly what the summary reports.
   */
  static final String CONFIG_VOLUME_EXPORT =
      """
      # qits-platform-deployments config volume, exported
      qits.platform.deployments.extras.story-import-alpha.env.QITS_FEATURE_FLAGS=trace-headers
      qits.platform.deployments.extras.story-import-alpha.mounts[0]=qits-alpha-data:/var/lib/alpha
      qits.platform.deployments.extras.story-import-beta.aliases[0]=beta.dev.localhost
      qits.platform.deployments.extras.story-import-beta.publishes[0]=8080:8080

      # the deployer's own keys ride in the same file and are none of this service's business
      qits.platform.deployments.orchestrator=swarm
      qits.platform.deployments.network=qits-net
      """;

  /**
   * A file whose second line carries the extras prefix and a key family this service does not know.
   * The <b>first</b> line is perfectly good, which is the point: it is what makes "nothing was
   * written" a claim about the transaction rather than about the parser.
   */
  static final String FILE_WITH_A_BAD_LINE =
      """
      qits.platform.deployments.extras.story-import-refused.env.QITS_OK=fine
      qits.platform.deployments.extras.story-import-refused.volumes[0]=nope
      """;

  /** Kept so {@code @AfterAll} can assert the bootstrap's bearer never reached the bundle. */
  private static String bootstrapBearer;

  /** The revision the second story finds the configuration standing at. */
  private static long revisionAfterTheFirstImport;

  /**
   * The inbound tap, once — the framework's own, idempotent per service, so installing it here as
   * well as in {@link TokenValidationBootstrapIT} draws nothing twice.
   */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * The far-side floor, in {@code @BeforeEach} because every story class does it the same way and
   * one of them ({@code stories.release}) has a fixture whose url needs {@code RestAssured.port} —
   * which the Quarkus integration-test extension sets in <b>beforeEach</b> and clears back to
   * {@code -1} in afterEach. Idempotent per JVM, so whichever class runs first takes the floor and
   * every class stays runnable on its own.
   *
   * <p>Nothing here provisions anything: these three stories write what they read.
   */
  @BeforeEach
  void floorTheEventLogRecording() {
    StoryEventBus.install();
  }

  @UserStory(value = "The config volume's file is imported whole", category = CATEGORY)
  @UserStoryDescription(
      """
      The platform's deployment configuration used to be a properties file on the deployer's own
      volume. The bootstrap hands that file to this service exactly as it was exported — comments,
      blank lines and the deployer's other keys included — and the answer says what happened to
      every line: four entries across two applications imported, five lines ignored because they
      were never this service's business, and nothing refused. From that moment the entries are
      platform state: versioned, attributed to the machine that wrote them, and served to whoever
      deploys the applications they name.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void theBootstrapImportsTheConfigVolume(Interactions story) {
    NetworkCapture.actor(BOOTSTRAP);
    bootstrapBearer = StoryIdentities.platformToken(BOOTSTRAP);

    given()
        .header("Authorization", "Bearer " + bootstrapBearer)
        .contentType(ContentType.TEXT)
        .body(CONFIG_VOLUME_EXPORT)
        .when()
        .post(StoryTarget.IMPORT_PATH)
        .then()
        .statusCode(200)
        .body("imported", equalTo(4))
        .body("unchanged", equalTo(0))
        // Five lines the file carried and this service does not read: the two comments, the blank
        // line and the deployer's own two keys. Reported rather than refused — the file belongs to
        // somebody else and only part of it was ever addressed to this service.
        .body("ignored", equalTo(5));
    story
        .note("the exported file is taken whole: four entries written, five lines not ours")
        .as("file-imported");

    List<Map<String, Object>> applications =
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.APPLICATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications");
    assertEquals(2, entryCountOf(applications, ALPHA), "both of alpha's keys are configured");
    assertEquals(2, entryCountOf(applications, BETA), "and both of beta's");
    story
        .note("the overview now names both applications the file configured")
        .as("applications-listed");

    JsonPath history =
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.historyPath(ALPHA))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<Map<String, Object>> revisions = history.getList("revisions");
    assertEquals(2, revisions.size(), "one revision per key the import actually changed");
    assertTrue(
        ((Number) revisions.get(0).get("seq")).longValue()
            > ((Number) revisions.get(1).get("seq")).longValue(),
        "the history is newest first — the log is read backwards from now");
    for (Map<String, Object> revision : revisions) {
      assertEquals(
          BOOTSTRAP,
          revision.get("updatedBy"),
          "a revision records who wrote it; for a machine that is the token's sub");
    }
    revisionAfterTheFirstImport =
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.resolvedPath(ALPHA))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("headRevision");
    assertTrue(revisionAfterTheFirstImport > 0, "the configuration stands at a real revision");
    story
        .note("every entry is a revision attributed to the machine that imported it")
        .as("history-attributed");
  }

  @UserStory(value = "Re-importing the same file writes nothing at all", category = CATEGORY)
  @UserStoryDescription(
      """
      The bootstrap runs on every boot, and it hands over the same file every time. An import that
      appended a revision per run would turn the history — the record that makes an accidental
      change answerable — into a log of how often the platform restarted. So a line whose value is
      already stored writes nothing: no revision, no re-attribution, no move of the head. The
      second run reports four lines unchanged, the head revision is exactly where the first run
      left it, and the history still holds two entries.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(2)
  void aSecondImportOfTheSameFileChangesNothing(Interactions story) {
    NetworkCapture.actor(BOOTSTRAP);

    long before =
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.resolvedPath(ALPHA))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("headRevision");
    assertEquals(revisionAfterTheFirstImport, before, "the story before this one left it here");
    story.note("the configuration stands where the first import left it").as("revision-before");

    given()
        .header("Authorization", "Bearer " + StoryIdentities.platformToken(BOOTSTRAP))
        .contentType(ContentType.TEXT)
        .body(CONFIG_VOLUME_EXPORT)
        .when()
        .post(StoryTarget.IMPORT_PATH)
        .then()
        .statusCode(200)
        .body("imported", equalTo(0))
        .body("unchanged", equalTo(4))
        .body("ignored", equalTo(5));
    story.note("the same file, a second time: four lines unchanged, none written").as("re-imported");

    assertEquals(
        revisionAfterTheFirstImport,
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.resolvedPath(ALPHA))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("headRevision"),
        "an identical value must not move the head");
    assertEquals(
        2,
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.historyPath(ALPHA))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions")
            .size(),
        "the history is a record of changes, not of runs");
    story
        .note("the head has not moved and the history has not grown")
        .as("history-unchanged");
  }

  @UserStory(value = "One malformed line refuses the whole file", category = CATEGORY)
  @UserStoryDescription(
      """
      A key becomes half of a property name qits-platform-deployments layers into its own
      configuration, and the deployer REFUSES a deployment carrying a key it does not recognise —
      by design, because a dropped flag is a container that boots, passes its gate and has lost its
      volume. Refusing the key here, at the write, turns that into a 400 the person who typed it
      reads instead of a failed deployment hours later. And because an import is ONE transaction,
      a bad line late in the file undoes the good ones before it: the application the file named
      resolves to nothing at revision zero, so the operator fixes one file rather than working out
      which half of it landed.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(3)
  void aBadLineLeavesNothingBehind(Interactions story) {
    NetworkCapture.actor(BOOTSTRAP);

    String message =
        given()
            .header("Authorization", "Bearer " + StoryIdentities.platformToken(BOOTSTRAP))
            .contentType(ContentType.TEXT)
            .body(FILE_WITH_A_BAD_LINE)
            .when()
            .post(StoryTarget.IMPORT_PATH)
            .then()
            .statusCode(400)
            .extract()
            .jsonPath()
            .getString("message");
    assertTrue(
        message != null && message.contains("volumes[0]"),
        "the refusal names the key it refused, which is what makes it fixable: " + message);
    story
        .note("the file is refused, and the refusal names the key rather than the line number")
        .as("file-refused");

    JsonPath resolved =
        StoryIdentities.platformService(given(), BOOTSTRAP)
            .when()
            .get(StoryTarget.resolvedPath(REFUSED))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        0,
        resolved.getMap("properties").size(),
        "the good line that came BEFORE the bad one must not have survived");
    assertEquals(
        0L,
        resolved.getLong("headRevision"),
        "and no revision was appended, so nothing to undo and nothing to explain");
    story
        .note("the application the file named holds nothing, at revision zero")
        .as("nothing-written");
  }

  private static int entryCountOf(List<Map<String, Object>> applications, String name) {
    return applications.stream()
        .filter(application -> name.equals(application.get("application")))
        .map(application -> ((Number) application.get("entries")).intValue())
        .findFirst()
        .orElse(-1);
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the import -----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, IMPORTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, IMPORTED_SLUG, "file-imported");
    ReportAssertions.assertStepId(CATEGORY, IMPORTED_SLUG, "applications-listed");
    ReportAssertions.assertStepId(CATEGORY, IMPORTED_SLUG, "history-attributed");
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPORTED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.IMPORT_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        IMPORTED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.historyPath(ALPHA) + " -> 200");
    // Four requests and no fifth arrow: writing the platform's configuration consults nobody. The
    // one caller is the machine that holds the file — see stories/deployment for the other end.
    ReportAssertions.assertEdgeCount(CATEGORY, IMPORTED_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, IMPORTED_SLUG, List.of(BOOTSTRAP));
    ReportAssertions.assertNotLeaked(CATEGORY, IMPORTED_SLUG, bootstrapBearer);

    // --- the re-import --------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REPEATED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REPEATED_SLUG, "revision-before");
    ReportAssertions.assertStepId(CATEGORY, REPEATED_SLUG, "re-imported");
    ReportAssertions.assertStepId(CATEGORY, REPEATED_SLUG, "history-unchanged");
    ReportAssertions.assertEdge(
        CATEGORY,
        REPEATED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.IMPORT_PATH + " -> 200");
    // The two resolved reads are one arrow: an edge is a dependency, and asking the same question
    // twice does not make two of them.
    ReportAssertions.assertEdgeCount(CATEGORY, REPEATED_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, REPEATED_SLUG, List.of(BOOTSTRAP));

    // --- the refusal ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "file-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "nothing-written");
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.IMPORT_PATH + " -> 400");
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSED_SLUG,
        NetworkEdge.HTTP,
        BOOTSTRAP,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(REFUSED) + " -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSED_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, REFUSED_SLUG, List.of(BOOTSTRAP));
  }
}
