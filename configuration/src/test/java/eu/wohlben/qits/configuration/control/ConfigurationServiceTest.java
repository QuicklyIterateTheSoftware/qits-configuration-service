package eu.wohlben.qits.configuration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import eu.wohlben.qits.configuration.dto.ImportSummaryDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.configuration.entity.ConfigurationRevision;
import eu.wohlben.qits.configuration.error.BadRequestException;
import eu.wohlben.qits.configuration.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The write seam, against a real PostgreSQL — embedded, spawned from a Maven artifact, never a
 * container.
 *
 * <p>Every test uses an application name of its own. The suite shares one database across classes
 * (Flyway cleans at start, not between tests), so a shared name would make one test's rows another
 * test's surprise.
 */
@QuarkusTest
class ConfigurationServiceTest {

  @Inject ConfigurationService configuration;

  @Test
  void aFirstWriteCreatesTheEntryAndOneRevision() {
    ConfigurationEntry entry =
        configuration.upsert("app-first", "env.QITS_REGISTRY", "localhost:8081", "alice");

    assertEquals("app-first", entry.application);
    assertEquals("env.QITS_REGISTRY", entry.entryKey);
    assertEquals("localhost:8081", entry.entryValue);
    assertEquals(ConfigurationEntry.CLASS_PLAIN, entry.entryClass);
    assertEquals("alice", entry.updatedBy);

    List<ConfigurationRevision> history = configuration.history("app-first");
    assertEquals(1, history.size());
    assertEquals("localhost:8081", history.get(0).entryValue);
    assertFalse(history.get(0).deleted);
    assertEquals(entry.headRevision, history.get(0).seq);
  }

  @Test
  void anIdenticalValueWritesNoRevision() {
    configuration.upsert("app-idempotent", "env.A", "one", "alice");
    long afterFirst = configuration.resolve("app-idempotent").headRevision();

    configuration.upsert("app-idempotent", "env.A", "one", "bob");

    assertEquals(1, configuration.history("app-idempotent").size());
    assertEquals(
        afterFirst,
        configuration.resolve("app-idempotent").headRevision(),
        "an identical write must not move the head revision");
    assertEquals(
        "alice",
        configuration.require("app-idempotent", "env.A").updatedBy,
        "an identical write must not re-attribute the entry either");
  }

  @Test
  void aChangedValueAppendsAndMovesTheHead() {
    ConfigurationEntry first = configuration.upsert("app-change", "env.A", "one", "alice");
    ConfigurationEntry second = configuration.upsert("app-change", "env.A", "two", "bob");

    assertTrue(second.headRevision > first.headRevision);
    assertEquals("two", second.entryValue);
    assertEquals("bob", second.updatedBy);

    List<ConfigurationRevision> history = configuration.history("app-change");
    assertEquals(2, history.size(), "history is newest first");
    assertEquals("two", history.get(0).entryValue);
    assertEquals("one", history.get(1).entryValue);
  }

  @Test
  void aDeleteRemovesTheEntryAndKeepsTheHistory() {
    configuration.upsert("app-delete", "env.A", "one", "alice");
    long beforeDelete = configuration.resolve("app-delete").headRevision();

    configuration.delete("app-delete", "env.A", "bob");

    assertThrows(NotFoundException.class, () -> configuration.require("app-delete", "env.A"));
    assertTrue(configuration.entriesOf("app-delete").isEmpty());

    List<ConfigurationRevision> history = configuration.history("app-delete");
    assertEquals(2, history.size());
    assertTrue(history.get(0).deleted);
    assertNull(history.get(0).entryValue, "a deletion records no value; the previous one is above");
    assertEquals("bob", history.get(0).updatedBy);
    assertTrue(
        configuration.resolve("app-delete").headRevision() > beforeDelete,
        "the head revision moves FORWARD on a delete — it comes from the log, not from the entries");
  }

  @Test
  void deletingWhatIsNotThereIsA404() {
    assertThrows(
        NotFoundException.class, () -> configuration.delete("app-absent", "env.A", "alice"));
  }

  @Test
  void aResolvedReadIsTheFullyPrefixedPropertyMap() {
    configuration.upsert("app-resolve", "env.QITS_A", "one", "alice");
    configuration.upsert("app-resolve", "mounts[0]", "/data:/data", "alice");

    ResolvedConfigurationDto resolved = configuration.resolve("app-resolve");

    assertEquals(2, resolved.properties().size());
    assertEquals(
        "one",
        resolved.properties().get("qits.platform.deployments.extras.app-resolve.env.QITS_A"));
    assertEquals(
        "/data:/data",
        resolved.properties().get("qits.platform.deployments.extras.app-resolve.mounts[0]"));
    assertTrue(resolved.headRevision() > 0);
  }

  @Test
  void anApplicationWithNothingStoredResolvesEmptyRatherThanFailing() {
    ResolvedConfigurationDto resolved = configuration.resolve("app-unconfigured");

    assertEquals(0, resolved.headRevision());
    assertTrue(resolved.properties().isEmpty());
  }

  @Test
  void theListingKeepsAnApplicationWhoseEntriesHaveAllBeenDeleted() {
    configuration.upsert("app-emptied", "env.A", "one", "alice");
    configuration.delete("app-emptied", "env.A", "alice");

    ApplicationSummaryDto summary =
        configuration.applications().stream()
            .filter(each -> each.application().equals("app-emptied"))
            .findFirst()
            .orElseThrow();

    assertEquals(0, summary.entries());
    assertTrue(summary.headRevision() > 0, "the history is still there and still says so");
  }

  @Test
  void anImportWritesTheLinesItRecognisesAndCountsTheRest() {
    ImportSummaryDto summary =
        configuration.importProperties(
            """
            # the deployer's config volume
            qits.platform.deployments.orchestrator=swarm
            qits.platform.deployments.extras.app-import.env.QITS_A=one
            qits.platform.deployments.extras.app-import.aliases[0]=app.dev.localhost
            """,
            "alice");

    assertEquals(2, summary.imported());
    assertEquals(0, summary.unchanged());
    assertEquals(2, summary.ignored(), "the comment and the deployer's own unrelated key");
    assertEquals(2, configuration.entriesOf("app-import").size());
  }

  @Test
  void reImportingTheSameFileWritesNothingAtAll() {
    String file =
        """
        qits.platform.deployments.extras.app-reimport.env.QITS_A=one
        qits.platform.deployments.extras.app-reimport.env.QITS_B=two
        """;
    configuration.importProperties(file, "alice");
    long afterFirst = configuration.resolve("app-reimport").headRevision();

    ImportSummaryDto again = configuration.importProperties(file, "alice");

    assertEquals(0, again.imported());
    assertEquals(2, again.unchanged());
    assertEquals(
        afterFirst,
        configuration.resolve("app-reimport").headRevision(),
        "an unchanged import leaves the log exactly as it found it");
    assertEquals(2, configuration.history("app-reimport").size());
  }

  @Test
  void aMalformedLineLeavesTheWholeImportUnwritten() {
    assertThrows(
        BadRequestException.class,
        () ->
            configuration.importProperties(
                """
                qits.platform.deployments.extras.app-atomic.env.QITS_A=one
                qits.platform.deployments.extras.app-atomic.volumes[0]=nope
                """,
                "alice"));

    assertTrue(
        configuration.entriesOf("app-atomic").isEmpty(),
        "the good line ahead of the bad one must not have survived");
  }

  /**
   * The pin report, walked in one method on purpose: "nothing pinned is an empty answer" is a claim
   * about a store no pin has been written into, and this suite shares one database across classes —
   * so it is asserted before this test writes rather than from a second method that might run after
   * it.
   *
   * <p>The application names here are the platform's real ones, because the map is a compile-time
   * constant and there is no pin on an invented application to write. Nothing else in this module
   * touches them.
   */
  @Test
  void thePinReportAnswersWhatIsStoredAndOmitsWhatWasNeverReleased() {
    assertTrue(
        configuration.imagePins().isEmpty(),
        "an environment that has released nothing pins nothing — not four rows with no version");

    configuration.upsert(
        "qits-projects", "env.QITS_PROJECTS_AGENT_IMAGE_VERSION", "2026.904.160152", "alice");
    configuration.upsert(
        "qits-workspaces", "env.QITS_WORKSPACE_IMAGE_VERSION", "2026.904.160522", "alice");

    assertEquals(
        List.of(
            new ImagePinDto(
                "qits/project-agent",
                "2026.904.160152",
                "qits-projects",
                "env.QITS_PROJECTS_AGENT_IMAGE_VERSION"),
            new ImagePinDto(
                "qits/workspace",
                "2026.904.160522",
                "qits-workspaces",
                "env.QITS_WORKSPACE_IMAGE_VERSION")),
        configuration.imagePins(),
        "the two unreleased mappings are omitted, and the refinement key of the workspace image is"
            + " one of them — the image is released, that entry is not written");
  }

  @Test
  void theKeyGrammarIsEnforcedOnTheWritePath() {
    assertThrows(
        BadRequestException.class, () -> configuration.upsert("app-guard", "volumes[0]", "x", null));
    assertThrows(
        BadRequestException.class, () -> configuration.upsert("Bad-App", "env.A", "x", null));
    assertTrue(configuration.entriesOf("app-guard").isEmpty());
  }
}
