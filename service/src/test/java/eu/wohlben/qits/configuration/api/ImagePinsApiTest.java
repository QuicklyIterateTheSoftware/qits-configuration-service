package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The pin report over the wire — the answer qits-artifacts' collector holds against age when it
 * decides which images it may delete.
 *
 * <p>The applications and keys are the platform's real ones, because the map is a compile-time
 * constant: there is no pin on an application of this test's own to write. Nothing else in this
 * suite touches them, and both tests begin by writing all four values, so neither depends on the
 * order the class is run in — the omission test puts back what it removed.
 *
 * <p><b>No test sends an identity header</b>, as in {@code ConfigurationApiTest}: qits-auth-core
 * ships a {@code %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped
 * {@code @RolesAllowed} pair is exercised rather than bypassed. What a refusal looks like is the
 * packaged catalogue's business — a {@code @QuarkusTest} cannot observe one.
 */
@QuarkusTest
class ImagePinsApiTest {

  private static final String BASE = "/configuration/api";

  private static final String AGENT_VERSION = "2026.904.160152";

  private static final String WORKSPACE_VERSION = "2026.904.160522";

  private static final String EDITOR_VERSION = "2026.904.100239";

  /** Every mapping of the map, written. Idempotent, so either test may run first. */
  private void pinEveryImage() {
    put("qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", AGENT_VERSION);
    put("qits-workspaces", "env.QITS_WORKSPACE_IMAGE_VERSION", WORKSPACE_VERSION);
    put("qits-projects", "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION", WORKSPACE_VERSION);
    put("qits-workspaces", "env.QITS_EDITOR_IMAGE_VERSION", EDITOR_VERSION);
  }

  private void put(String application, String key, String value) {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest(value))
        .when()
        .put(BASE + "/applications/" + application + "/entries/" + key)
        .then()
        .statusCode(oneOf(200, 201));
  }

  /**
   * Four mappings, four rows, in the order the contract names — image, then application, then key —
   * and {@code qits/workspace} twice, because two applications start it and each holds its own
   * entry.
   */
  @Test
  void everyConfiguredImageVersionIsARowInTheMapsOrder() {
    pinEveryImage();

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        // An ISO instant rather than an epoch number: a receipt quotes when it asked.
        .body("generatedAt", endsWith("Z"))
        .body("pins.size()", equalTo(4))
        .body("pins[0].image", equalTo("qits/project-agent"))
        .body("pins[0].version", equalTo(AGENT_VERSION))
        .body("pins[0].application", equalTo("qits-projects"))
        .body("pins[0].key", equalTo("env.QITS_PROJECTS_AGENT_IMAGE_VERSION"))
        .body("pins[1].image", equalTo("qits/workspace"))
        .body("pins[1].version", equalTo(WORKSPACE_VERSION))
        .body("pins[1].application", equalTo("qits-projects"))
        .body("pins[1].key", equalTo("env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"))
        .body("pins[2].image", equalTo("qits/workspace"))
        .body("pins[2].version", equalTo(WORKSPACE_VERSION))
        .body("pins[2].application", equalTo("qits-workspaces"))
        .body("pins[2].key", equalTo("env.QITS_WORKSPACE_IMAGE_VERSION"))
        .body("pins[3].image", equalTo("qits/workspace-editor"))
        .body("pins[3].version", equalTo(EDITOR_VERSION))
        .body("pins[3].application", equalTo("qits-workspaces"))
        .body("pins[3].key", equalTo("env.QITS_EDITOR_IMAGE_VERSION"));
  }

  /**
   * A mapping with nothing stored is omitted rather than answered with a blank version: the image
   * has never been released into this environment, and a row naming {@code qits/workspace-editor:}
   * would be a tag that cannot exist.
   */
  @Test
  void aMappingWithNothingStoredHasNoRow() {
    pinEveryImage();

    given()
        .when()
        .delete(BASE + "/applications/qits-workspaces/entries/env.QITS_EDITOR_IMAGE_VERSION")
        .then()
        .statusCode(204);

    given()
        .when()
        .get(BASE + "/pins")
        .then()
        .statusCode(200)
        .body("pins.size()", equalTo(3))
        .body("pins.image", everyItem(not(equalTo("qits/workspace-editor"))));

    // Put it back: the other test asserts all four, and the suite shares one database.
    put("qits-workspaces", "env.QITS_EDITOR_IMAGE_VERSION", EDITOR_VERSION);
  }
}
