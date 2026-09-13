package eu.wohlben.qits.configuration.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An agent reads what it reads today, under its own role.
 *
 * <p>Agents will stop inheriting their owner's roles and hold {@code qits:agent} instead. Every
 * {@code GET} here accepts that role; no write does.
 *
 * <p>The identity is the forward-auth pair. With {@code X-Qits-User} present, the roles are exactly
 * {@code X-Qits-Roles}, so the {@code %test} dev user's roles do not leak in. Seeding sends no
 * header and so runs as that dev user.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String BASE = "/configuration/api";
  private static final String APP = "agent-reads";
  private static final String ENV = "test";
  private static final String KEY = "env.QITS_AGENT_READS";
  private static final String YAML = "application/yaml";

  /** RestAssured has no encoder for YAML; it is sent as text, as DeclarationsApiTest does. */
  private static final RestAssuredConfig YAML_AS_TEXT =
      RestAssuredConfig.config()
          .encoderConfig(EncoderConfig.encoderConfig().encodeContentTypeAs(YAML, ContentType.TEXT));

  private static final String DOCUMENT =
      """
      keys:
        env.QITS_AGENT_READS:
          type: string
          default: hello
      """;

  @BeforeEach
  void seed() {
    given()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("one"))
        .when()
        .put(entry())
        .then()
        .statusCode(oneOf(200, 201));
    given()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(DOCUMENT)
        .when()
        .post(declaration() + "?deploymentTarget=environment")
        .then()
        .statusCode(oneOf(200, 201));
  }

  private static String entry() {
    return BASE + "/applications/" + APP + "/envs/" + ENV + "/entries/" + KEY;
  }

  private static String declaration() {
    return BASE + "/applications/" + APP + "/declarations/1.0";
  }

  private static RequestSpecification asAgent() {
    return given()
        .header("X-Qits-User", "dyn-workspace-agent-reads")
        .header("X-Qits-Roles", "qits:agent");
  }

  @Test
  void anAgentReadsTheConfiguration() {
    String envBase = BASE + "/applications/" + APP + "/envs/" + ENV;
    asAgent().when().get(BASE + "/applications").then().statusCode(200);
    asAgent().when().get(envBase + "/resolved").then().statusCode(200);
    asAgent().when().get(envBase + "/entries").then().statusCode(200);
    asAgent().when().get(envBase + "/history").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheDeclarations() {
    asAgent().when().get(BASE + "/applications/" + APP + "/declarations").then().statusCode(200);
    asAgent().when().get(declaration()).then().statusCode(200);
  }

  @Test
  void anAgentReadsTheImagePins() {
    asAgent().when().get(BASE + "/pins").then().statusCode(200);
  }

  @Test
  void anAgentCannotWrite() {
    asAgent()
        .contentType(ContentType.JSON)
        .body(new ConfigurationController.SetEntryRequest("two"))
        .when()
        .put(entry())
        .then()
        .statusCode(403);
    // The client shows this message to a person, so a 403 carries one like every other refusal.
    asAgent()
        .when()
        .delete(entry())
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("message", org.hamcrest.Matchers.notNullValue());
    asAgent()
        .contentType(ContentType.TEXT)
        .body("qits.platform.deployments.extras." + APP + ".env.QITS_AGENT_READS=three\n")
        .when()
        .post(BASE + "/import?env=" + ENV)
        .then()
        .statusCode(403);
    asAgent()
        .config(YAML_AS_TEXT)
        .contentType(YAML)
        .body(DOCUMENT)
        .when()
        .post(BASE + "/applications/" + APP + "/declarations/2.0?deploymentTarget=environment")
        .then()
        .statusCode(403);
    asAgent().when().delete(declaration()).then().statusCode(403);
  }
}
