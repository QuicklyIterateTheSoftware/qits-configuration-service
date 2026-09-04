package eu.wohlben.qits.configuration.control;

import eu.wohlben.qits.configuration.dto.ApplicationSummaryDto;
import eu.wohlben.qits.configuration.dto.ImagePinDto;
import eu.wohlben.qits.configuration.dto.ImportSummaryDto;
import eu.wohlben.qits.configuration.dto.ResolvedConfigurationDto;
import eu.wohlben.qits.configuration.entity.ConfigurationEntry;
import eu.wohlben.qits.configuration.entity.ConfigurationRevision;
import eu.wohlben.qits.configuration.error.NotFoundException;
import eu.wohlben.qits.configuration.persistence.ConfigurationEntryRepository;
import eu.wohlben.qits.configuration.persistence.ConfigurationRevisionRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The whole of what this service does: store a configuration entry, version it, and serve it.
 *
 * <p><b>Every write goes through {@link #store} and there is no second door.</b> Appending the
 * revision and moving the head is one decision, and splitting it across two callers is how a head
 * ends up naming a revision that says something else. The import path calls the same method in a
 * loop rather than a bulk variant of its own.
 *
 * <p><b>An identical value writes nothing.</b> {@link #store} compares before it appends, so a
 * re-import of an unchanged file leaves the log exactly as it found it. That is what makes the
 * import safe to run from a bootstrap on every boot, and it keeps the history a record of changes
 * rather than of runs.
 *
 * <p><b>The write brackets are {@link DbRetry#inNewTx} and each body ends with a flush.</b> This
 * service is deployed beside the postgres it stores in and is redeployed by the component that
 * redeploys that postgres, so a connection dying mid-write is an ordinary event rather than an
 * exotic one. {@code inNewTx} owns the transaction boundary, which is the only way a retry can tell
 * "the body threw, so it certainly never committed" from "the transaction manager reported it"; the
 * flush is what keeps a lost connection on the body's side of that line, since an ORM would
 * otherwise put every statement on the far side of the undecidable round trip.
 *
 * <p>Reads are deliberately NOT wrapped. A read that fails is a 500 the caller retries; the deployer
 * pulling a resolved read has its own timeout and its own posture about an unreachable
 * configuration service, and a patience here would only make its deadline arrive with less
 * information.
 */
@ApplicationScoped
public class ConfigurationService {

  @Inject ConfigurationEntryRepository entries;

  @Inject ConfigurationRevisionRepository revisions;

  // ---------------------------------------------------------------- reads

  /**
   * Every application this service knows about, by name.
   *
   * <p>It is the UNION of the two tables rather than a listing of the head rows, so an application
   * whose entries have all been deleted still appears — with no entries and the revision seq that
   * deleted the last one. Dropping it would make the one case a person most wants to look at (where
   * did my configuration go) the one case the listing hides.
   */
  public List<ApplicationSummaryDto> applications() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (ConfigurationEntry entry : entries.listEverything()) {
      counts.merge(entry.application, 1, Integer::sum);
    }
    TreeSet<String> names = new TreeSet<>(counts.keySet());
    names.addAll(
        revisions
            .getEntityManager()
            .createQuery(
                "select distinct r.application from ConfigurationRevision r", String.class)
            .getResultList());
    List<ApplicationSummaryDto> summaries = new ArrayList<>(names.size());
    for (String name : names) {
      summaries.add(
          new ApplicationSummaryDto(
              name, counts.getOrDefault(name, 0), revisions.headRevisionOf(name)));
    }
    return summaries;
  }

  /** One application's current entries, by key. */
  public List<ConfigurationEntry> entriesOf(String application) {
    return entries.listByApplication(ConfigurationKeys.requireApplication(application));
  }

  /**
   * One application's configuration as the property map a consumer layers verbatim.
   *
   * <p>An application with nothing stored is an empty map at revision 0, not a 404: "this
   * application has no extras" is a complete and useful answer, and a deployer that treated a 404 as
   * an error would refuse every deployment of an application nobody has configured.
   */
  public ResolvedConfigurationDto resolve(String application) {
    String app = ConfigurationKeys.requireApplication(application);
    Map<String, String> properties = new LinkedHashMap<>();
    for (ConfigurationEntry entry : entries.listByApplication(app)) {
      properties.put(ExtrasProperties.propertyName(app, entry.entryKey), entry.entryValue);
    }
    return new ResolvedConfigurationDto(revisions.headRevisionOf(app), properties);
  }

  /**
   * THE PIN REPORT: every {@link ImagePins} mapping that currently has a stored version, in the
   * answer's fixed order.
   *
   * <p><b>An entry with nothing stored is omitted rather than answered blank.</b> No entry means the
   * image has never been released into this environment, so there is no version, and a row carrying
   * an empty one would name a tag that cannot exist. Every mapping missing is an empty list, which
   * is a complete answer and not an error — a platform that has released nothing pins nothing.
   *
   * <p>It reads the head rows one mapping at a time, which is four point-reads on a primary key
   * today. A listing filtered in memory would be shorter to write and would quietly grow with the
   * table instead of with the map.
   *
   * <p>Not wrapped in a retry, like every other read here: the caller — qits-artifacts' collector,
   * deciding what it may delete — has its own posture about an unreachable configuration service,
   * and it is a fail-closed one. Patience here would only make its deadline arrive with less
   * information.
   */
  public List<ImagePinDto> imagePins() {
    List<ImagePinDto> pins = new ArrayList<>(ImagePins.ORDERED.size());
    for (ImagePins.Pin pin : ImagePins.ORDERED) {
      entries
          .findEntry(pin.application(), pin.key())
          .map(entry -> entry.entryValue)
          .filter(version -> !version.isBlank())
          .ifPresent(
              version ->
                  pins.add(new ImagePinDto(pin.image(), version, pin.application(), pin.key())));
    }
    return pins;
  }

  /** One application's history, newest first. */
  public List<ConfigurationRevision> history(String application) {
    return revisions.listByApplication(ConfigurationKeys.requireApplication(application));
  }

  /** One current entry, or a 404 naming it. */
  public ConfigurationEntry require(String application, String key) {
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    return entries
        .findEntry(app, entryKey)
        .orElseThrow(
            () -> new NotFoundException("No entry " + entryKey + " for application " + app));
  }

  // ---------------------------------------------------------------- writes

  /**
   * Set one entry's value. New keys are created, existing ones moved; an identical value writes no
   * revision and returns the entry it found.
   */
  public ConfigurationEntry upsert(String application, String key, String value, String actor) {
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    String entryValue = ConfigurationKeys.requireValue(value);
    return DbRetry.inNewTx(
        "set " + ExtrasProperties.propertyName(app, entryKey),
        () -> {
          ConfigurationEntry stored = store(app, entryKey, entryValue, actor);
          entries.flush();
          return stored;
        });
  }

  /**
   * Remove one entry. It appends a deleted revision and takes the head row away — the history keeps
   * the value that was removed, which is what makes an accidental delete answerable.
   */
  public void delete(String application, String key, String actor) {
    String app = ConfigurationKeys.requireApplication(application);
    String entryKey = ConfigurationKeys.requireKey(key);
    DbRetry.runInNewTx(
        "remove " + ExtrasProperties.propertyName(app, entryKey),
        () -> {
          ConfigurationEntry existing =
              entries
                  .findEntry(app, entryKey)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "No entry " + entryKey + " for application " + app));
          append(app, entryKey, null, true, actor);
          entries.delete(existing);
          entries.flush();
        });
  }

  /**
   * Bulk import of an extras properties file, in the full prefixed spelling.
   *
   * <p>ONE TRANSACTION for the whole file, so a malformed line late in it leaves nothing behind —
   * an import that half-applied would be worse than one that failed, because the operator would
   * have to work out which half.
   *
   * <p>Idempotent by construction: it calls {@link #store}, which appends nothing when the value is
   * already what the line says.
   */
  public ImportSummaryDto importProperties(String text, String actor) {
    List<ExtrasProperties.Parsed> lines = ExtrasProperties.parse(text);
    int ignored = countLines(text) - lines.size();
    return DbRetry.inNewTx(
        "import " + lines.size() + " configuration entries",
        () -> {
          int imported = 0;
          int unchanged = 0;
          for (ExtrasProperties.Parsed line : lines) {
            String app = ConfigurationKeys.requireApplication(line.application());
            String key = ConfigurationKeys.requireKey(line.key());
            String value = ConfigurationKeys.requireValue(line.value());
            if (wouldChange(app, key, value)) {
              store(app, key, value, actor);
              imported++;
            } else {
              unchanged++;
            }
          }
          entries.flush();
          return new ImportSummaryDto(imported, unchanged, ignored);
        });
  }

  // ---------------------------------------------------------------- the seam

  /**
   * THE ONE WRITE. It appends the revision and moves the head in the caller's transaction, and
   * nothing else in this class writes a row.
   *
   * <p>The revision is flushed before the head is written, because the head names the revision's
   * generated seq and an identity column has no value until the insert has run.
   */
  private ConfigurationEntry store(String application, String key, String value, String actor) {
    Optional<ConfigurationEntry> found = entries.findEntry(application, key);
    if (found.isPresent() && Objects.equals(found.get().entryValue, value)) {
      return found.get();
    }
    ConfigurationRevision revision = append(application, key, value, false, actor);
    ConfigurationEntry entry = found.orElseGet(ConfigurationEntry::new);
    if (found.isEmpty()) {
      entry.id = UUID.randomUUID();
      entry.application = application;
      entry.entryKey = key;
      entry.entryClass = ConfigurationEntry.CLASS_PLAIN;
    }
    entry.entryValue = value;
    entry.headRevision = revision.seq;
    entry.updatedAt = revision.updatedAt;
    entry.updatedBy = actor;
    if (found.isEmpty()) {
      entries.persist(entry);
    }
    return entry;
  }

  private ConfigurationRevision append(
      String application, String key, String value, boolean deleted, String actor) {
    ConfigurationRevision revision = new ConfigurationRevision();
    revision.application = application;
    revision.entryKey = key;
    revision.entryValue = value;
    revision.deleted = deleted;
    revision.updatedBy = actor;
    // Truncated to the column's own precision, so a value read back equals the one written rather
    // than differing in digits the database never kept.
    revision.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    revisions.persist(revision);
    revisions.flush();
    return revision;
  }

  private boolean wouldChange(String application, String key, String value) {
    return entries
        .findEntry(application, key)
        .map(entry -> !Objects.equals(entry.entryValue, value))
        .orElse(true);
  }

  private static int countLines(String text) {
    return text == null || text.isBlank() ? 0 : (int) text.lines().count();
  }
}
