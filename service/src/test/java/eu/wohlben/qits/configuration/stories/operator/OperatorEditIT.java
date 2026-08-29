package eu.wohlben.qits.configuration.stories.operator;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.configuration.stories.bootstrap.ConfigurationImportIT;
import eu.wohlben.qits.configuration.stories.deployment.DeploymentConfigurationIT;
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
 * <b>What an operator does here</b>, and what the store does about it — the live edit that this
 * service was built to make possible.
 *
 * <p>The thing it replaced was a properties file on the deployer's own volume, snapshotted at
 * deployer boot: an edit was inert until somebody forced a reload, and a live fix was reverted by
 * the next deploy. An operator now writes here instead, through the browser session the platform
 * edge asserts, and the next deployment of that application reads it. Three stories, and each one is
 * a property of the write seam rather than of the route:
 *
 * <ol>
 *   <li>a value is set, and an identical re-save writes <b>no revision at all</b> — the history is a
 *       record of changes, not of saves. Every revision names the person who made it, because the
 *       edge's {@code X-Qits-User} is the principal and the principal is what is recorded;
 *   <li>a delete keeps the value in the history and moves the head <b>forward</b>. That number is
 *       read from the append-only log rather than as a maximum over the current entries, which is
 *       the difference between a revision that can only advance and one that walks backwards when
 *       something is removed — and a consumer records it to say which configuration it deployed
 *       with;
 *   <li>a key the deployer would not recognise is refused <b>at the write</b>. The deployer rejects
 *       a deployment carrying a key it does not know, by design, because a dropped flag is a
 *       container that boots, passes its gate and has lost its volume. Refusing here turns that
 *       into a 400 the person who typed it reads.
 * </ol>
 *
 * <p>This service parses no VALUES, and that line is the whole boundary: what a mount, a published
 * port or an alias means is qits-platform-deployments' {@code ServiceExtras}, which stays the single
 * parser on the platform. Only the shape of the key is this service's business.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OperatorEditIT {

  static final String CATEGORY = "operator";

  static final String SET_SLUG = "an-operator-sets-a-value-and-saving-it-again-changes-nothing";
  static final String DELETED_SLUG = "a-removed-entry-stays-in-the-history-and-the-head-moves-forward";
  static final String REFUSED_SLUG = "a-key-the-deployer-would-not-recognise-is-refused-at-the-write";

  /** The person, as the diagram names them. The edge asserts who they are; this is what they are. */
  static final String OPERATOR = "an operator";

  /** …and the name that reaches the audit trail, because {@code X-Qits-User} is the principal. */
  static final String OPERATOR_USER = "dana";

  /** The application this class configures. A literal — see {@link StoryTarget}. */
  static final String APPLICATION = "story-operator-app";

  static final String KEY = "env.QITS_LOG_LEVEL";

  static final String FIRST_VALUE = "debug";

  static final String SECOND_VALUE = "trace";

  /** A key that means to be an environment variable and is not a legal name for one. */
  static final String BAD_KEY = "env.9BAD";

  /** An application name that is not dns-label shaped — the other half of the grammar. */
  static final String BAD_APPLICATION = "Story-Operator";

  /** The revision the first story's edits left the application at. */
  private static long revisionAfterTheEdits;

  /** The inbound tap, once. The framework's own, idempotent per service. */
  @BeforeAll
  static void tapWhatAStorySends() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
  }

  /**
   * The far-side floor, in {@code @BeforeEach} for the reason every story class here has it there:
   * {@code RestAssured.port} is set by the Quarkus integration-test extension's beforeEach callback,
   * and the classes that provision through it must not run before that. Idempotent per JVM.
   */
  @BeforeEach
  void floorTheEventLogRecording() {
    StoryEventBus.install();
  }

  @UserStory(
      value = "An operator sets a value, and saving it again changes nothing",
      category = CATEGORY)
  @UserStoryDescription(
      """
      An operator raises an application's log level in the browser. The first write creates the
      entry — 201 — and the second, with the value unchanged, answers 200 and appends nothing at
      all: no revision, and no re-attribution of the entry either. That idempotency is what makes a
      seeding script free to re-run and what keeps the history readable; a log that grew a row per
      save would answer "when was this last saved" instead of "what changed, and who changed it".
      Changing the value really does append, and both revisions name the person who made them —
      the edge asserts X-Qits-User, this service records the principal, and nothing about who
      edited what is ever a guess.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class
  })
  @Order(1)
  void anOperatorSetsAValueTwice(Interactions story) {
    NetworkCapture.actor(OPERATOR);

    JsonPath created =
        StoryIdentities.person(given(), OPERATOR_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("value", FIRST_VALUE))
            .when()
            .put(StoryTarget.entryPath(APPLICATION, KEY))
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    assertEquals(FIRST_VALUE, created.getString("entry.value"));
    assertEquals(
        OPERATOR_USER,
        created.getString("entry.updatedBy"),
        "the entry records the person the edge said was asking");
    long firstRevision = created.getLong("entry.revision");
    assertTrue(firstRevision > 0, "a new entry names the revision that created it");
    story
        .note("the entry does not exist yet, so the write creates it: 201, attributed to the operator")
        .as("entry-created");

    JsonPath unchanged =
        StoryIdentities.person(given(), OPERATOR_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("value", FIRST_VALUE))
            .when()
            .put(StoryTarget.entryPath(APPLICATION, KEY))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        firstRevision,
        unchanged.getLong("entry.revision"),
        "an identical value appends no revision, so the entry still names the one that created it");
    story
        .note("saving the same value again answers 200 and writes no revision at all")
        .as("identical-save-is-free");

    JsonPath changed =
        StoryIdentities.person(given(), OPERATOR_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("value", SECOND_VALUE))
            .when()
            .put(StoryTarget.entryPath(APPLICATION, KEY))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    revisionAfterTheEdits = changed.getLong("entry.revision");
    assertTrue(revisionAfterTheEdits > firstRevision, "a real change does append, and the head moves");

    List<Map<String, Object>> revisions =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.historyPath(APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions");
    assertEquals(2, revisions.size(), "two changes, two revisions — the identical save is not one");
    assertEquals(SECOND_VALUE, revisions.get(0).get("value"), "newest first");
    assertEquals(FIRST_VALUE, revisions.get(1).get("value"), "and the value it replaced is still there");
    for (Map<String, Object> revision : revisions) {
      assertEquals(OPERATOR_USER, revision.get("updatedBy"));
    }
    story
        .note("the history holds the two changes, newest first, both attributed to the operator")
        .as("history-holds-the-changes");
  }

  @UserStory(
      value = "A removed entry stays in the history and the head moves forward",
      category = CATEGORY)
  @UserStoryDescription(
      """
      Removing an entry takes it out of what the deployer will read, and out of nothing else. A
      deleted revision is appended with the value that was removed, so an accidental delete is
      answerable rather than merely regrettable — somebody can read what the value used to be.
      The head revision therefore moves FORWARD across a delete, which is only true because it is
      read from the append-only log: taken as a maximum over the entries that still exist it would
      move backwards, and a number a consumer records to say "this container was deployed with
      configuration N" is not one that can go back.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class
  })
  @Order(2)
  void anOperatorRemovesAnEntry(Interactions story) {
    NetworkCapture.actor(OPERATOR);

    JsonPath before =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.resolvedPath(APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    long revisionBefore = before.getLong("headRevision");
    assertEquals(revisionAfterTheEdits, revisionBefore, "the story before this one left it here");
    assertTrue(
        before.getMap("properties").containsKey(propertyName()),
        "the entry is part of what a deployment of this application would start with");
    story.note("the entry is currently part of the application's deployment").as("entry-present");

    StoryIdentities.person(given(), OPERATOR_USER)
        .when()
        .delete(StoryTarget.entryPath(APPLICATION, KEY))
        .then()
        .statusCode(204);
    story.note("the operator removes it").as("entry-removed");

    JsonPath after =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.resolvedPath(APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertFalse(
        after.getMap("properties").containsKey(propertyName()),
        "the next deployment of this application will not carry it");
    assertTrue(
        after.getLong("headRevision") > revisionBefore,
        "the head moved FORWARD over a delete — it is read from the log, never as a maximum over"
            + " the entries that are left");
    story
        .note("it is gone from the deployer's read, and the head revision has moved forward")
        .as("head-moved-forward");

    List<Map<String, Object>> revisions =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.historyPath(APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("revisions");
    assertEquals(3, revisions.size(), "the delete is a revision like any other");
    assertEquals(Boolean.TRUE, revisions.get(0).get("deleted"), "and it says what it was");
    assertNull(revisions.get(0).get("value"), "a delete carries no value of its own");
    assertEquals(
        SECOND_VALUE,
        revisions.get(1).get("value"),
        "the value that was removed is still readable, which is what makes this answerable");
    story
        .note("the history keeps the deletion AND the value it removed")
        .as("value-still-readable");
  }

  @UserStory(
      value = "A key the deployer would not recognise is refused at the write",
      category = CATEGORY)
  @UserStoryDescription(
      """
      qits-platform-deployments refuses a deployment carrying an extras key it does not recognise,
      and that refusal is deliberate: a flag it silently dropped would be a container that boots,
      passes its health gate and has lost its volume. Checking the key HERE, at the moment somebody
      types it, turns that into a 400 with a sentence naming what is wrong — instead of a failed
      deployment hours later, by which time nobody is looking at the edit. The same grammar covers
      the application segment, which is dns-label shaped because the name reaches network aliases
      and image paths over there. Neither refusal writes anything: the application stays absent
      from the overview and the entry never appears.
      """)
  @UserflowRunsAfter({
    TokenValidationBootstrapIT.class,
    ConfigurationImportIT.class,
    DeploymentConfigurationIT.class
  })
  @Order(3)
  void aKeyOutsideTheGrammarIsRefused(Interactions story) {
    NetworkCapture.actor(OPERATOR);

    String keyMessage =
        StoryIdentities.person(given(), OPERATOR_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("value", "please-no"))
            .when()
            .put(StoryTarget.entryPath(APPLICATION, BAD_KEY))
            .then()
            .statusCode(400)
            .extract()
            .jsonPath()
            .getString("message");
    assertTrue(
        keyMessage != null && keyMessage.contains(BAD_KEY),
        "the refusal names the key and what an environment variable may look like: " + keyMessage);
    story
        .note("`env.9BAD` is not a name a shell would take, so it is refused with a sentence saying so")
        .as("key-refused");

    String applicationMessage =
        StoryIdentities.person(given(), OPERATOR_USER)
            .contentType(ContentType.JSON)
            .body(Map.of("value", "please-no"))
            .when()
            .put(StoryTarget.entryPath(BAD_APPLICATION, KEY))
            .then()
            .statusCode(400)
            .extract()
            .jsonPath()
            .getString("message");
    assertTrue(
        applicationMessage != null && applicationMessage.contains(BAD_APPLICATION),
        "and the same for an application name that is not dns-label shaped: " + applicationMessage);
    story
        .note("an application name that is not dns-label shaped is refused the same way")
        .as("application-refused");

    List<String> keys =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.entriesPath(APPLICATION))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("entries.key", String.class);
    assertFalse(keys.contains(BAD_KEY), "the refused key was never stored");

    List<String> applications =
        StoryIdentities.person(given(), OPERATOR_USER)
            .when()
            .get(StoryTarget.APPLICATIONS_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications.application", String.class);
    assertFalse(
        applications.contains(BAD_APPLICATION),
        "and the refused application never came into existence — a name is created by a write");
    story
        .note("neither refusal left anything behind: no entry, and no application")
        .as("nothing-created");
  }

  private static String propertyName() {
    return "qits.platform.deployments.extras." + APPLICATION + "." + KEY;
  }

  @AfterAll
  static void everyStoryReportIsComplete() {
    // --- the edit -------------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, SET_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SET_SLUG, "entry-created");
    ReportAssertions.assertStepId(CATEGORY, SET_SLUG, "identical-save-is-free");
    ReportAssertions.assertStepId(CATEGORY, SET_SLUG, "history-holds-the-changes");
    // The 201 and the 200 are two arrows because the STATUS is part of the label: "created" and
    // "already there" are the two answers this route gives, and the diagram shows both.
    ReportAssertions.assertEdge(
        CATEGORY,
        SET_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(APPLICATION, KEY) + " -> 201");
    ReportAssertions.assertEdge(
        CATEGORY,
        SET_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(APPLICATION, KEY) + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SET_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.historyPath(APPLICATION) + " -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, SET_SLUG, 3);
    // One initiator: an edit here is rows in this service's own store, and the deployer learns of
    // it by asking, next time it deploys. Nothing is pushed anywhere.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SET_SLUG, List.of(OPERATOR));

    // --- the delete -----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, DELETED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DELETED_SLUG, "entry-present");
    ReportAssertions.assertStepId(CATEGORY, DELETED_SLUG, "head-moved-forward");
    ReportAssertions.assertStepId(CATEGORY, DELETED_SLUG, "value-still-readable");
    ReportAssertions.assertEdge(
        CATEGORY,
        DELETED_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "DELETE " + StoryTarget.entryPath(APPLICATION, KEY) + " -> 204");
    ReportAssertions.assertEdge(
        CATEGORY,
        DELETED_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.resolvedPath(APPLICATION) + " -> 200");
    // The two resolved reads either side of the delete are one arrow: an edge is a dependency, and
    // asking the same question twice does not make two of them.
    ReportAssertions.assertEdgeCount(CATEGORY, DELETED_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, DELETED_SLUG, List.of(OPERATOR));

    // --- the refusal ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "key-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "application-refused");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "nothing-created");
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSED_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(APPLICATION, BAD_KEY) + " -> 400");
    ReportAssertions.assertEdge(
        CATEGORY,
        REFUSED_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "PUT " + StoryTarget.entryPath(BAD_APPLICATION, KEY) + " -> 400");
    ReportAssertions.assertEdgeCount(CATEGORY, REFUSED_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, REFUSED_SLUG, List.of(OPERATOR));
  }
}
