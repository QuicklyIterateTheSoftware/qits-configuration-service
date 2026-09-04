# qits-configuration-service

Deployment configuration as platform state: the entries an environment's applications are deployed
with, stored, versioned and served.

## What it replaces

qits-platform-deployments reads a deployment's extra environment, mounts, published ports, groups
and network aliases from `qits.platform.deployments.extras.<app>.*`. Those keys lived in a
hand-edited properties file on the deployer's own config volume, snapshotted at deployer boot — so
an edit was inert until the deployer was forced to reload, and a live fix applied to a running
service was reverted by the next deploy.

This service owns the entries instead. Every write is versioned and attributed; the deployer pulls
the resolved answer per deployment and records the revision it deployed with. Nothing pushes
configuration into a deployment: the deployer *reads*, with its own machine identity.

## The model

Two tables, and the second is the authority.

    configuration_revision   append-only. Every write that changes something adds exactly one row:
                             (application, key, value, deleted, seq, updatedBy, updatedAt).
    configuration_entry      the read-optimised HEAD. One row per (application, key) that currently
                             has a value, naming the revision it came from.

The current state is reproducible from the log alone, which is what makes an accidental edit
answerable rather than merely regrettable. A delete appends a revision and removes the head row, so
the value that was removed is still readable.

**An identical write appends nothing.** That is what makes a bootstrap free to re-import its file on
every boot, and it keeps the history a record of changes rather than of runs.

### The key grammar

A key is the extras grammar *after* the application segment:

    env.<VAR>       VAR matches [A-Za-z_][A-Za-z0-9_]*
    mounts[i]       i is one to four digits
    publishes[i]
    groups[i]
    aliases[i]

An application name is dns-label shaped. Anything else is a 400 that names what is wrong.

**This service parses no values.** What a mount, a published port or an alias *means* is read by
qits-platform-deployments' own `ServiceExtras`, which stays the single parser on the platform. The
key's shape is checked here because the deployer refuses a deployment carrying a key it does not
recognise — checking at the write turns that into a 400 the person who typed it reads, instead of a
failed deployment hours later.

## The API

Everything under `/configuration/api`. Every route accepts `qits:admin` (a person, through
qits-gateway's forward-auth headers) or `qits:system` (a machine, through a bearer validated against
qits-platform-idp). There is no anonymous route.

| route | what it answers |
| --- | --- |
| `GET /applications` | every configured application, with its entry count and head revision |
| `GET /applications/{app}/resolved` | **the deployer's read** — `{headRevision, properties}`, the properties at their full `qits.platform.deployments.extras.<app>.<key>` names |
| `GET /applications/{app}/entries` | the current entries |
| `PUT /applications/{app}/entries/{key}` | set one value. 201 the first time, 200 after; an identical value writes no revision |
| `DELETE /applications/{app}/entries/{key}` | remove one entry, keeping it in the history |
| `GET /applications/{app}/history` | every revision, newest first |
| `POST /import` | `text/plain`, an extras properties file whole. Idempotent; answers `{imported, unchanged, ignored}` |
| `GET /pins` | the configured container-image versions — `{generatedAt, pins:[{image, version, application, key}]}` |

The resolved read carries **complete property names** on purpose: a consumer layers the map as a
configuration source verbatim, with no prefix to re-assemble and no second place for the deployer's
namespace to be written down. That namespace has moved twice already.

### The pin report

`GET /pins` answers one row per image→(application, key) mapping in `control/ImagePins` that
currently has a stored version, ordered by image, then application, then key. An image appears twice
when two applications start it — `qits/workspace` is a workspace and a refinement container — and a
mapping with nothing stored is **omitted**, because an image nobody has released here has no version
to name. An empty `pins` is an ordinary 200.

It is a projection of entries a caller could read one at a time; what it adds is **the map**, which
lives in this service and nowhere else. **qits-artifacts' garbage collector reads it as a pin
source**: a configured version is one a container launch will pull *cold*, so the registry's own
last-accessed record says nothing about it and deleting it is a workspace that will not start. An
image outside the map is not launchable-by-configuration and needs no row.

The same list is what `bus/SoftwareReleaseListener` matches a release against — one definition, so
the pin mechanism and the pin report cannot disagree.

The framework's own paths sit under `/configuration/q` — `/configuration/q/health/ready` is what the
deployer's health gate curls, and `/configuration/q/openapi` is the document.

## Running the tests

    git submodule update --init            # the client; `verify` runs `package`, which builds it
    ./mvnw clean verify

No docker, no network beyond Maven Central, the platform's own Maven repository and the npm
registries the client installs from. The suite spawns a real PostgreSQL of its own — zonky's
binaries, resolved as ordinary Maven artifacts and started as a child process.

To probe the packaged artifact as well:

    ./mvnw clean verify -DskipITs=false     # the fast-jar
    sdk env && ./mvnw clean verify -Dnative # the GraalVM binary

## The modules

    configuration/  the domain — entity, persistence, control, dto, mapper, error. No JAX-RS. Owns
                    the datasource, the persistence unit and the Flyway lineage.
    service/        the adapters — the JAX-RS routes, the exception mapper, and the native-image
                    reflection registration for what Jackson binds.

    service/src/main/webui/  the client — qits-configuration-frontend, a git submodule. Quinoa
                             builds it during `package` and serves it at / on this service's own
                             host.

## The client

`service/src/main/webui` is the
[qits-configuration-frontend](https://github.com/QuicklyIterateTheSoftware/qits-configuration-frontend)
submodule, an Angular application Quinoa builds during `package` and serves at the **root** of this
service's own host, `configuration.<env>.<domain>`: the applications listing, one application's
entries with the editor, and its history. The same pages are addressable per repository —
`/<projectSlug>/<category>/<repoName>/…` — which is the URL grammar every SPA on this platform
shares.

The root is spelled twice — `quarkus.quinoa.ui-root-path` here and `baseHref` in the submodule's
`angular.json`, both `/` — and the two move together; `PackagedSurfaceIT` asserts the agreement,
because a mismatch serves a page whose every asset 404s with nothing on this side to notice.

**`quarkus.quinoa.ignored-path-prefixes=/configuration` is what keeps the client from swallowing the
API.** The SPA fallback is a late-order catch-all over the whole host, and the deployer's
per-deployment read lives under `/configuration/api` — a machine path answered with `200 index.html`
would hand a JSON parser an HTML document on the one service whose answer decides what a container
starts with. One entry covers the segment, because the match is by prefix.

**The bundle is built before the image, never inside it.** `@qits/ui-components` lives only on the
platform's own npm registry, which no `RUN` in a docker build can reach; `.config/qits/ci-post-receive.yml`
builds it in the step container, and `docker/Dockerfile` neuters Quinoa's install and build commands
and guards the staged bundle before the native compile.

So a **clone-alone build now means clone AND `git submodule update --init`, with a node on PATH**:
`verify` runs `package`, and `package` needs both. `./mvnw test` still needs neither, because Quinoa
is off in test mode.
