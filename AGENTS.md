# qits-configuration — working notes

Read `README.md` first: it defines the model and lists the routes. This file is the working
conventions on top of it.

## The rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior `mvn
install` elsewhere, no credentials. That is why the poms duplicate versions instead of inheriting
them, and why the suite spawns its own PostgreSQL from a Maven artifact rather than reaching for a
container.

**The one thing it needs besides Maven Central** is the platform's own Maven repository, for
`qits-db-core`, `qits-auth-core` and `qits-arch-rules`. `<repositories>` in the root pom points at
`${qits.maven.repository.url}`; the image build overrides it through `.qits-maven-settings.xml`,
which mirrors the exact repository id `qits-maven` — an exact id match is what gets past Maven's
`external:http:*` blocker.

**The gate is `./mvnw clean verify`**, and since the client landed it needs BOTH a node on PATH and
`git submodule update --init` — `verify` runs `package`, and `package` is where Quinoa builds
`service/src/main/webui`. An empty webui/ stops it at "No package.json found in Web UI directory".
`./mvnw test` still needs neither, because Quinoa is off in test mode. Always `clean` — incremental
compilation leaves stale MapStruct `*Impl` classes behind when a mapper's shape changes.

**`quarkus.http.test-port=0`, deliberately.** Quarkus' default test port is 8081, which on the
deployment host is the published address of the platform's own npm registry.

**`service/` compiles to a GraalVM native image.** `.sdkmanrc` names `25.0.2-graalce`, so `sdk env`
gives you a `native-image` and `./mvnw verify -Dnative` produces `service/target/qits-configuration`
and runs `PackagedSurfaceIT` against it. A missing GraalVM does not fail the build — Quarkus falls
back to a container build, so recognise the fallback by the image pull.

**Anything returned as `Response.entity(...)` is invisible to the build-time Jackson analysis**,
which is what `api/ApiWireReflection` exists for. A new response type joins that list in the commit
that adds it; the failure is a 500 in the native binary while every JVM test stays green.

## This service stores; it does not parse

The line is the whole boundary. `ConfigurationKeys` validates the SHAPE of an application name and
of a key. **Nothing here reads a value.** What a mount, a published port, a group or an alias means
is qits-platform-deployments' `ServiceExtras`, which stays the single parser on the platform — a
second one would be a second opinion about what a deployment means, and it would be the copy no real
deployment exercises.

The key grammar is checked at the write because the deployer **refuses** a deployment carrying a key
it does not recognise, by design: a dropped flag is a container that boots, passes its gate and has
lost its volume. Refusing at the write turns that into a 400 the person who typed it reads.

If the deployer's grammar grows a family, it grows here in the same wave — and here **first**, since
a key this service refuses can never reach a deployment at all.

## One write seam, and why the head cannot drift

`ConfigurationService.store` is the only method in the repository that writes a row. It appends the
revision and moves the head in one transaction; the import path calls it in a loop rather than
having a bulk variant of its own. Splitting that is how a head ends up naming a revision that says
something else.

Three things about it that are decisions rather than details:

- **An identical value writes nothing at all** — no revision, and no re-attribution of the entry
  either. That is the idempotency the import rests on, and it is asserted from both sides
  (`anIdenticalValueWritesNoRevision`, `reImportingTheSameFileWritesNothingAtAll`).
- **The revision is flushed before the head is written.** The head names the revision's generated
  seq, and an identity column has no value until the insert has run.
- **`headRevision` is read from the LOG, never as a maximum over the heads.** A delete appends a
  revision and takes a head row away, so a maximum over the heads would move *backwards* — and a
  consumer records this number to say which configuration it deployed with. A number that can go
  back is not one. `aDeleteRemovesTheEntryAndKeepsTheHistory` pins it.

**An import is one transaction for the whole file.** A malformed line late in the file leaves
nothing behind; a half-applied import would be worse than a failed one, because the operator would
have to work out which half.

**Every write is a `DbRetry.inNewTx` whose body ends with a `flush()`.** `inNewTx` owns the
transaction boundary, which is the only way a retry can tell "the body threw, so it certainly never
committed" from "the transaction manager reported it" — Narayana spells a lost commit and a real
rollback with the same exception. The flush is what keeps a lost connection on the body's side of
that line. Reads are deliberately not wrapped: the deployer has its own timeout and its own posture
about an unreachable configuration service, and patience here would only make its deadline arrive
with less information.

## Identity: two tracks, one set of roles

Authentication happens elsewhere. A request with no `Authorization` header is USER traffic —
qits-gateway performed the login and asserted `X-Qits-User` / `X-Qits-Roles`, which qits-auth-core's
`ForwardAuthMechanism` reads. A request WITH a bearer is MACHINE traffic, validated by quarkus-oidc
against qits-platform-idp.

**Both land as roles, which is why every route is `@RolesAllowed({"qits:admin", "qits:system"})` and
none of them calls `MachineAuth.require()`.** The reads are pulled by the deployer once per
deployment *and* read by an operator; the writes are made by an operator *and* by the bootstrap's
import. A machine-only guard on either side would lock the other one out. There is no anonymous
route here and there must never be one.

`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` — validation follows the rollout
gate rather than standing on its own, so with the gate off there is no OIDC tenant, nothing fetches
a JWKS, and a clone-alone build needs no issuer. There is no third state.

**This service is credential-bearing infrastructure.** Its database is what a deployment's
environment is read from, so treat its write surface with the sensitivity of the
`qits-deployments-config` volume it replaces.

## Schema changes

`configuration/src/main/resources/db/configuration/migration/`, hand-written, its own lineage on its
own datasource. Keep appending, never edit an applied migration.

The suites run every migration against an **empty** schema, so a backfill is untested by them. A
migration that backfills needs a test that migrates to the version before, writes the rows the old
code wrote, and migrates the rest of the way — `Flyway.configure().target("<version>")` stops
halfway.

Write inserts in such tests with **named columns**. A positional one makes every later migration a
change to a test that had nothing to do with it.

**Three Java fields do not match their columns**, and that is deliberate: `entryKey`, `entryValue`
and `entryClass` are stored as `key`, `value` and `class`. `KEY` and `VALUE` are reserved in HQL and
`class` is a Java keyword; all three are legal unquoted column names in PostgreSQL. Renaming the
columns to match instead would have put the mismatch where a person reads SQL by hand.

**No check constraint on `class`**, so the vocabulary can grow without a migration and every
historical row keeps the word it was written with. `plain` is the only word v1 writes; `secret` is
the qits-secrets fold-in and arrives with code that can hold one.

## Adding a dependency on another context

Don't. This component depends on three published qits jars — `qits-db-core`, `qits-auth-core` and
`qits-arch-rules` (test scope) — and all three are **platform libraries rather than contexts**:
shared machinery, no domain, no entity of anyone else's.

`application` is a plain `varchar` with no FK, even though qits-platform-deployments has a row for
it: that row lives in another physical database, and configuration outlives the catalogue entry that
described it.

**`quarkus-undertow` must never be on the classpath.** It arrives transitively from anything
servlet-shaped, and since the client landed it is load-bearing rather than principled: Quinoa serves
the bundle through Quarkus' own static-resource route, which undertow's servlet stack takes over and
then cannot find — a packaged process that answers the API correctly and the SPA with a 404.

    ./mvnw -pl service -am dependency:tree | grep -i undertow

## The client

`service/src/main/webui` is the `qits-spa-configuration` submodule (relative url, `ignore = all`,
`update = merge`, `branch = main` — the sibling shape). Quinoa 2.8.2 is pinned by hand in the root
pom, because Quinoa is in no BOM and its version does not track the platform's.

- **The segment is spelled twice**, `quarkus.quinoa.ui-root-path` here and `baseHref` in the
  submodule's `angular.json`. A mismatch serves a page whose every asset 404s and nothing on this
  side notices, so `PackagedSurfaceIT` asserts the `<base href>` string rather than the status.
- **`ignored-path-prefixes` values are RELATIVE**, matched after `ui-root-path` is stripped: `/api`
  and `/q`, never `/configuration/api`. An absolute value matches nothing and is indistinguishable
  from an unset key. Setting the key REPLACES Quinoa's derivation, which is why both are spelled by
  hand. **Add a literal route under `/configuration` and its entry here in the same commit** — and
  give it a segment of its own, because an entry protects a segment and not a string prefix.
- **The bundle is built OUTSIDE the docker build.** `@qits/ui-components` exists only on the
  platform's own npm registry, which a `RUN` reaches by no address at all — and npm's answer to a
  registry that never connects is `Exit handler never called!`, naming neither. So
  `.config/qits/ci-post-receive.yml` builds it in the step container (on qits-net) and the Dockerfile
  neuters Quinoa's install/ci/build commands with `--version`, guards the staged bundle with a
  `test -f` before the multi-minute native compile, and `cp`s the bundle onto itself so Quinoa's
  MOVE does not hit overlayfs' EXDEV.
- **Quinoa is off in test mode and stays off.** A `@QuarkusTest` asserting anything about
  `/configuration/` would pass against a process with no client in it, so every claim about the SPA
  belongs in `PackagedSurfaceIT`.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus merges the test resources over it rather than replacing it. Never
  re-declare an app-level setting in test resources: a test copy is free to drift from the shipped
  one, and then a green suite proves nothing about what actually starts.
- **No dev services and no containers, ever.** `EmbeddedPg` starts zonky's postgres — real binaries
  resolved as Maven artifacts, spawned as a child process — and `EmbeddedPgConfigSource` hands its
  url, username and password to every `@QuarkusTest` at an ordinal above `application.properties`,
  because the port is chosen at run time and cannot be written down. Both are **copied** per module
  rather than shared: a test-jar dependency between two modules that have none is the higher price.
  Each module names its own database (`configuration_test`, `configuration_svc`). Testcontainers is
  not on this classpath and must not arrive.
- The suite shares one database across classes (Flyway cleans at start, not between tests), so
  **every test names an application of its own** and assertions read with `hasItem`, never a size,
  wherever they span applications.
- **A `@QuarkusTest` runs under the `test` profile, where qits-auth-core ships a dev user** carrying
  `qits:admin` and `qits:system` — so the shipped `@RolesAllowed` pair is exercised rather than
  bypassed, with no `@TestSecurity` fabricating an identity that no deployment ever produces.
- **The identity contract is real only against the artifact**, which is what `PackagedSurfaceIT` and
  every class under `stories/` run against: the launched process runs as a deployment does, so its
  requests carry `X-Qits-User` / `X-Qits-Roles` or a bearer, and a request with neither is refused.
  `PackagedSurfaceIT` is the SPA and datasource half of that (and the only one `-Dnative` runs); the
  story catalogue is the behaviour half — see § Userflows. `PackagedSurfaceIT`
  also hands the process `QITS_RESOURCE_DB_*` rather than restating the datasource keys, so the
  jar's own `${…}` indirection is under test, and it reads the written row back over JDBC to prove
  which database the process really opened. Its embedded postgres reaches the profile through a
  **system property**, because a `QuarkusTestProfile` is instantiated in more than one classloader
  and a static field is not shared between them.
- ITs are skipped by default; `-DskipITs=false` runs against the fast-jar and `-Dnative` against the
  binary.

## Deliberately not here yet

Each of these is a decision, not an omission, and each has a place it would land:

- **Secret entries.** The `class` column exists for them and holds `plain` only. A secret is an
  in-memory, approval-gated, one-shot credential — never a value in this table.
- **Change events.** This service SUBSCRIBES (`bus/SoftwareReleaseListener` consumes qits-ci's
  `SoftwareRelease` off the durable bus) and publishes nothing: no `ConfigurationChanged`, no
  announcement of a write. The deployer pulls per deployment, so nothing depends on a push. A
  publisher would arrive with the vocabulary jar every other announcing service has.
- **OpenTelemetry export.** The siblings ship `quarkus-opentelemetry` with the four preview keys
  spelled out. This service does not yet; adding it is the extension plus that block, copied from
  qits-events' `application.properties` where the reasoning for each line lives.
- **A committed `docs/openapi.yml`.** The document is served at `/configuration/q/openapi`; there is
  no export test writing it into the repo. Add one the day the surface has an outside consumer whose
  diffs are worth reviewing.

## The packaged IT with the tenant on — the profile the whole catalogue runs behind

`api/TokenValidationBootstrapIT` boots the **packaged** fast-jar with the **machine-auth gate on** —
`qits.auth.machine.required=true`, which is what `quarkus.oidc.tenant-enabled` is spelled in terms of
— against `eu.wohlben.qits.servicemock.idp.MockIdp`, a recording stand-in for qits-platform-idp that
serves a real JWKS for a generated keypair and mints RS256 bearers signed by it. That is the one
posture no other test here reaches: every `@QuarkusTest` leaves the gate shut (a clone-alone
`./mvnw verify` must need no issuer), so the block this service deploys with — `auth-server-url`
plus `jwks-path=jwks` fetched over a real listener at startup, `quarkus.oidc.token.audience`
enforcement, the `groups` claim becoming roles — is exercised nowhere else. The stake is the
deployer: its per-deployment `resolved` read is a bearer's read, so this is the precondition of
every deployment on the platform.

Five things about it are easy to undo:

- **Its profile EXTENDS `PackagedSurfaceIT.PackagedUnderTarget`** rather than copying it — what a
  launched qits-configuration needs in order to boot is one answer — and adds the gate, the mock
  idp's address, the eventstream resource triple, and the bus pointed at a stub.
- **`PackagedWithMockIdp` is the ONE profile every story class shares**, which is what makes the
  whole catalogue one launched process and one embedded postgres instead of one per class. Every
  shared seam belongs in it; a second profile is a second boot, and — because `MockIdp` and the
  event-log stub are parked in system properties per JVM — a second startup JWKS fetch draining into
  whichever story happens to be open.
- **That triple is a gap in the parent, not a decision of the child.** The qits-eventstream jar ships
  `${QITS_RESOURCE_EVENTSTREAM_URL}` with no default (the refuse-to-boot stance), and
  `PackagedSurfaceIT` predates the jar and still hands the launched process the `QITS_RESOURCE_DB_*`
  triple alone — so `-DskipITs=false` on that test alone dies at Flyway naming the missing variable.
  Move the three lines up into `PackagedUnderTarget` when that is fixed.
- **Every override the profile sets is a RUNTIME key**, including the ones that look like
  environment (they are spelled as the deployer spells them, so the shipped `${…}` expressions stay
  under test). A packaged process cannot be handed a build-time key; it would silently take the
  default.
- **The story classes are opted in by NAME, not by `skipITs`.** The root pom keeps `skipITs=true`,
  because failsafe has one run per module and half of `PackagedSurfaceIT` is about the SPA, which the
  userflow pipeline deliberately does not build (`-Dquarkus.quinoa=false`). The list is spelled in
  `.config/qits/ci-event-userflows.yml` and repeated under § Userflows below; **a new story class has
  to be added to it**, or it is written and never run.

## Userflows

`service/src/test/java/eu/wohlben/qits/configuration/stories/` is this repository's **user-story
catalogue**, and `api/TokenValidationBootstrapIT` is the boot story it runs behind. Each `@UserStory`
method is a browserless walk (an `Interactions` parameter, sometimes a `Network` one, and no `Flow`,
so the transitive Playwright launches nothing) that emits
`service/target/userstories/<category>/<story>/` — a `userflow.json` sidecar, a markdown rendering
and a self-contained HTML page carrying the story's **network diagram**. The framework is
`qits-userflows`, test scope, pinned on its own `qits.userflows.version` because it is released out
of `libs/qits-userflows` and not out of the integrations reactor.

**Every story is a `@QuarkusIntegrationTest` against the packaged artifact, and that is not a
preference.** Inside a `@QuarkusTest` qits-auth-core's `%test` dev user holds all four platform roles
and the OIDC tenant is off, so *every door in this service is open to a plain `given()`* — a refusal
cannot be observed at all. A launched artifact runs in `NORMAL` mode with the tenant on and no dev
user, which is the first moment "no credential", "the wrong role" and `qits:system` mean anything.
That is why `stories/refusals/` exists and why it cannot move into the surefire suite.

**Class order is FQCN-alphabetical within the profile group, and the package names are chosen so
alphabetical IS the intended order**: `api` (the boot) → `bootstrap` → `deployment` → `operator` →
`refusals` → `release`, which is also the order the platform meets them in — the configuration is
imported, the deployer reads it, an operator changes it, the doors are shown to be shut, and a
release writes into it without anybody typing. Every story also declares `@UserflowRunsAfter`, so a
later package rename cannot silently reshuffle the diagrams. The order is load-bearing rather than
tidy: a cumulative capture source is attributed by a cursor, so traffic recorded before any story ran
— the startup JWKS fetch — lands in whichever story drains *first*, and that must be the story about
it. Every class is nonetheless runnable on its own (`-Dit.test=ImageReleasePinIT`), because the
fixture and the far-side floor are per-JVM idempotent rather than per-order.

**The diagram is observed, never narrated.** `Interactions` records notes; nothing draws an edge by
hand except the one declared store. Three taps feed `NetworkCapture` and there is no fourth:

| tap | what it draws | where it lives |
| --- | --- | --- |
| `NetworkTaps.restAssured("qits-configuration")` | `<actor> -> qits-configuration`, one edge per request a story makes, labelled `METHOD <scrubbed path> -> <status>` | the framework ships it; installed from each story class's `@BeforeAll`, idempotent per service |
| `MockIdp.recordedRequests()` | `qits-configuration -> qits-platform-idp` — the startup JWKS fetch | registered as a cumulative `NetworkCapture.source` in `api/TokenValidationBootstrapIT` |
| the event-log stub's access log | `qits-configuration -> qits-events` — the catch-up poll that carried a release | `stories/support/StoryEventBus` |

The local `StoryNetworkFilter` this repo carried beside the IT is **deleted**: the framework ships
that tap now (`qits-userflows` 2026.829.201516), and a per-repo copy is exactly the thing that goes
out of step. Its default skip is any path with a `/q/` segment, which is right here —
`quarkus.http.non-application-root-path` is `/configuration/q`, so the readiness probe is out of
every diagram and no route this service owns is.

**The bus is LIT in the story profile, and that is the finding worth carrying.** Every other suite in
this repository darkens `qits.eventstream.enabled`, and a launched artifact would have dialled the
real `qits-events` alias — so the one path by which a value enters this store without a person typing
it was exercised nowhere. `stories/support/StoryEventBus` is a small recording HTTP server that
answers the log's list route: it is started from the profile (the launched process needs
`qits.events.url` before it boots), **armed by a file** (a test profile is instantiated in more than
one classloader, so the story that arms the log does not hold the object that serves it), and it
records `METHOD URI STATUS carried|empty` per answered request. `qits.eventstream.catchup-interval` is
shortened to `PT2S`, so a release arrives on a tick rather than on a push — the stub cannot upgrade a
websocket, and the durable consumer does not need it to.

**Two things are excluded from that recording, and neither is silent.** An **empty poll** is not an
edge: the catch-up sweep is a timer that fires whether or not a story is running, so drawing it would
put a heartbeat in whichever story happened to be open and the `networkHash` would move with nothing
having changed. The **websocket redial** (`/events/stream`, answered 404) is out for the same reason.
What is kept is the poll that *carried* the release, which is a dependency a story exercised and can
point at. Skipping happens at harvest and a skipped line never enters the list, so the framework's
per-source cursor still slices a prefix-stable sequence.

**Fixture setup must be invisible to the tap.** `stories/support/StoryPlatform` configures the one
shared application with a plain `java.net.http.HttpClient` — the RestAssured tap is JVM-global once
installed, so a fixture built through `given()` would draw arrows nobody walked. It goes in
`@BeforeEach`, not `@BeforeAll`: `RestAssured.port` is set by the Quarkus integration-test extension's
*beforeEach* callback and cleared back to `-1` in afterEach, so a `@BeforeAll` that builds a url from
it produces `http://localhost:-1`. Both it and `StoryEventBus.install()` are idempotent per JVM, and
the order (**provision first, floor second**) is what keeps fixture traffic below the line.

**Application names in stories are stable literals, never run stamps.** A name is a whole path
segment and `Labels` rewrites only segments it can tell were generated — a uuid, a long hex run, a
bare number — so `story-import-alpha` would survive into the label exactly as written and a stamped
name would move every `networkHash` on every run. Each class owns its own names, and the embedded
postgres is new per run, so literals cost nothing.

**What the stories claim, and where the negatives are.** A presence check cannot say "and nothing
else happened", which is most of what is worth knowing about a store:

| category | story | the claim only a negative can make |
| --- | --- | --- |
| `authentication` | the startup JWKS fetch; a stranger's token refused | — |
| `bootstrap` | the config volume's file imported whole; re-importing writes nothing; one bad line refuses the whole file | `assertEdgeCount` + one initiator — writing the platform's configuration consults nobody |
| `deployment` | the deployer's resolved read; an unconfigured application still deploys | `assertEdgeCount(2)` on the read — one request in, one **declared** jdbc store behind it, and `assertNoEdgesTo(qits-platform-idp)`: a bearer is judged on keys fetched at startup, so the idp is not on the critical path of every deployment |
| `operator` | a value set and re-saved; an entry removed; a key outside the grammar refused | `assertOnlyEdgesFrom(<one person>)` — an edit is rows in this service's own store and is pushed nowhere |
| `authorization` | anonymous; a signed-in reader; both identity tracks at one door | `assertNoEdgesFrom(qits-configuration)` — a refusal is decided at the door, so no store is read on behalf of a caller about to be refused |
| `release` | a released image becomes what the next container starts with | `assertEdgeCount(4)` however many times the story polled, and the one **outgoing** arrow in the catalogue that is not the idp |

The declared jdbc edge is the honest answer to "what does this service call out to": its own
postgres, and nothing else, while it serves the read every deployment on the platform waits for.
`Network.declare` is the framework's escape hatch for a dependency no tap can see, marked
`"declared": true` in the sidecar and drawn muted and dashed, so a claim never renders like evidence.

**What is out of reach here.** The live half of the bus — a frame pushed over `/events/stream` — is
not covered: the stub is a `com.sun` `HttpServer` and cannot upgrade a websocket, so every delivery
in these stories is the catch-up sweep's. That is the *durable* path and the one that survives a
restart, so it is the more load-bearing of the two; the live path is qits-eventstream's own suite's
business. Nothing here exercises the native binary either — `-Dnative` runs `PackagedSurfaceIT`, and
the story classes would work under it unchanged.

**Running them:**

    ./mvnw -pl service -am -DskipITs=false -Dquarkus.quinoa=false verify \
      -Dtest=SKIPNONE -Dsurefire.failIfNoSpecifiedTests=false \
      -Dit.test=TokenValidationBootstrapIT,ConfigurationImportIT,DeploymentConfigurationIT,OperatorEditIT,AccessRefusalIT,ImageReleasePinIT

`-Dit.test` takes commas; `-Dtest=SKIPNONE` keeps the unit suite out of an IT-only run (run it
separately before committing). `skipITs` stays `true` in the root pom because `PackagedSurfaceIT` is
half about the SPA, so the opt-in is per-run and per-class. The class orderer is installed the one way
Quarkus permits — the `junit.quarkus.orderer.secondary-orderer` line in `service`'s test properties; a
local `junit-platform.properties` hard-fails surefire.

`.config/qits/ci-event-userflows.yml` publishes the reports per commit as the docs bundle
`@userflows/qits-configuration`, and is **non-gating by design**: it is a separate file from
`ci-post-receive.yml` so a red story does not cost the branch its image. It runs exactly the list
above.
