# qits-configuration-platform-service

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
                             (env, application, key, value, deleted, seq, updatedBy, updatedAt).
    configuration_entry      the read-optimised HEAD. One row per (env, application, key) that
                             currently has a value, naming the revision it came from.

The current state is reproducible from the log alone, which is what makes an accidental edit
answerable rather than merely regrettable. A delete appends a revision and removes the head row, so
the value that was removed is still readable.

**An entry is addressed by (env, application, key).** This service runs on the platform plane and
holds every environment's configuration in one store, so there is no "the" configuration of an
application — there is dev's and there is prod's. What the old one-instance-per-tier deployment
asserted is a column now, and every route that reaches a row names the env it means. The env-less
spellings that carried callers across the plane move are gone, and so is the one property that
answered for them.

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

**This service parses no entry values.** What a mount, a published port or an alias *means* is read
by qits-platform-deployments' own `ServiceExtras`, which stays the single parser on the platform. The
key's shape is checked here because the deployer refuses a deployment carrying a key it does not
recognise — checking at the write turns that into a 400 the person who typed it reads, instead of a
failed deployment hours later.

### Declarations, the one document this service does parse

`.config/qits/configuration.yml` is where an application declares its **own** keys. It has a closed
top level carrying a single `keys:` mapping, and each key names one of five types:

    keys:
      env.QITS_GREETING:      { type: string,  default: hello }     # also boolean, number
      env.QITS_EVENTS_URL:    { type: serviceAddress, service: qits-events, port: 8080 }
      env.QITS_IMAGE_VERSION: { type: packageVersion, package: { type: docker, name: qits/workspace } }

`description:` is allowed on every key, kept in the stored document and given no field of its own.
Everything else is refused, at every level, naming the document and the key.

**Why the boundary moves for this one and not for entry values.** A declaration asks questions no
application can answer about itself. A `serviceAddress` renders to a different host in every
environment, and to a *differently shaped* host depending on which plane the named service deploys
onto — bare `qits-events` on the platform plane, `<env>-qits-ci` on the environment plane, which is
qits-platform-deployments' own `PdNetworks.alias`. The service that answers the resolved read is the
only place both facts meet, so it is the one parser (`control/DeclarationParser`) and there must not
be a second.

**The plane comes with the seed, not out of the file.** `?deploymentTarget=platform|environment` is
the deployer's fact — `deployment_target` in that application's own `.config/qits/deployments.yml` —
and a document asserting its own would be a second answer that diverges the first time a service is
promoted.

**A version names one document.** The same bytes again is a 200 that appends nothing; different bytes
under a taken version is a 409 naming both hashes. `DELETE` is the tag-recovery door for a re-cut
build, and after it the previously-governing version governs again.

**Precedence, lowest first: declared default → imported value → operator's value.** The last two are
one stored row ordered at the *write*: the bulk import writes class `imported` and refuses to
overwrite a `plain` row, reporting how many it `kept`. Without that, a bootstrap that re-imports on
every boot would silently revert an operator's fix to a live environment. A `serviceAddress` key sits
outside the ladder entirely — it is rendered on every read, a `PUT` on one is a 400, and a stored row
on one is reported `orphaned`.

## The API

Everything under `/configuration/api`. Every route accepts `qits:admin` (a person, through
qits-gateway's forward-auth headers) or `qits:system` (a machine, through a bearer validated against
qits-platform-idp) — **except the two declaration writes, which take `qits:system` alone and also
call `MachineAuth.require()`**. That is not a claim that declarations are more dangerous than
entries; it is that every other route records a *decision*, which a person may legitimately make,
while a declaration records an *asserted fact about a build*, which only the pipeline that built it
can honestly make. Every `GET` also accepts `qits:agent`, the role an agent holds on its own token. There is
no anonymous route.

| route | what it answers |
| --- | --- |
| `GET /applications` | every configured application, with a row per env it is configured in — entry count and head revision each |
| `GET /applications/{app}/envs/{env}/resolved?version=` | **the deployer's read** — `{headRevision, properties}`, the properties at their full `qits.platform.deployments.extras.<app>.<key>` names. With `?version=` it is **the overlay read**: that version's declaration merged underneath, defaults for keys nobody set, `serviceAddress` keys rendered for *this* env. Without it, exactly the entries. **Never a 404 either way**: a version that names no declaration resolves entries-only too, because the unmigrated estate deploys through this read with the version it is deploying |
| `GET /applications/{app}/envs/{env}/entries` | the current entries of that env, each flagged `orphaned` against the governing declaration |
| `PUT /applications/{app}/envs/{env}/entries/{key}` | set one value. 201 the first time, 200 after; an identical value writes no revision |
| `DELETE /applications/{app}/envs/{env}/entries/{key}` | remove one entry, keeping it in the history |
| `GET /applications/{app}/envs/{env}/history` | every revision of that env, newest first |
| `POST /import?env=` | `text/plain`, an extras properties file whole, into the env the caller names. Idempotent; answers `{imported, unchanged, kept, ignored}` |
| `GET /pins` | the configured container-image versions — `{generatedAt, pins:[{image, version, application, key}]}` |
| `POST /applications/{app}/declarations/{version}?deploymentTarget=` | `application/yaml`, the document raw. **`qits:system` + `MachineAuth`.** 201 new, 200 identical, 409 different-under-a-taken-version, 422 unreadable |
| `DELETE /applications/{app}/declarations/{version}` | the tag-recovery door. **`qits:system` + `MachineAuth`.** 204; the previous version governs again |
| `GET /applications/{app}/declarations` | every version declared, newest first, with the governing one flagged |
| `GET /applications/{app}/declarations/{version}` | one declaration: the parsed keys **and** the document verbatim |

**There is no env-less spelling of an entry route.** `/applications/{app}/resolved`, `/entries`,
`/entries/{key}`, `/history` and `POST /import` with no `?env=` existed across the plane move,
delegating to one configured env named by `QITS_CONFIGURATION_LEGACY_ENV`; the last caller of one
stopped asking, and they went with the setting — a fresh platform has no legacy env, so requiring
one was a variable an operator had to invent before the service would boot. A caller that still asks
for one gets a 404, and an import with no `?env=` a 400. The declaration routes have no env spelling
either, for a different reason: a declaration is a fact about a build and is the same fact in every
tier.

The import takes its env from the caller because the file cannot carry one — the grammar is
`extras.<application>.<key>` and has nowhere to put a tier — so the assertion is made once, for the
whole file.

The resolved read carries **complete property names** on purpose: a consumer layers the map as a
configuration source verbatim, with no prefix to re-assemble and no second place for the deployer's
namespace to be written down. That namespace has moved twice already. **They carry no env**, and must
not: the map is layered into one container's configuration, and that container is in exactly one
environment — the one named in the path of the read.

### The pin report

`GET /pins` answers one row per image→(application, key)→version that is currently stored, ordered by
image, then application, then key. An image appears twice when two applications start it —
`qits/workspace` is a workspace and a refinement container — or when two envs hold different versions
of it; a mapping with nothing stored anywhere is
**omitted**, because an image nobody has released here has no version to name. An empty `pins` is an
ordinary 200.

**The mappings come from two places and declarations win.** A `packageVersion` key in an
application's own declaration says which package that key carries a version of, and every governing
one of `type: docker` is a mapping; `control/ImagePins` is the residual list of pins still carried by
hand for consumers that have not declared yet, and a row of it is dropped when a declaration names
the same (application, key). A `binary` coordinate is a real declaration and stays out of this
answer, which is about container images. **The route is env-less because its caller is, and it reads
the UNION over every env**: qits-artifacts asks about a registry the whole platform shares, so the
answer has no tier in it — but two tiers on two versions of one image are two tags in use, and both
are reported. Two tiers on the same version are one row, since the shape carries no env.

It is a projection of entries a caller could read one at a time; what it adds is **the mapping**,
which lives in this service and nowhere else. **qits-artifacts' garbage collector reads it as a pin
source**: a configured version is one a container launch will pull *cold*, so the registry's own
last-accessed record says nothing about it and deleting it is a workspace that will not start. An
image nothing maps to is not launchable-by-configuration and needs no row.

`bus/SoftwareReleaseListener` matches an announced release against the same two sources through the
same merge function, and writes the version into **every env this store knows about** — an entry is a
per-env override with no default row beneath it, so an env a release does not reach starts its
containers on the image's committed default. One definition of what is pinned, so the pin mechanism
and the pin report cannot disagree.

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
    service/        the adapters — the JAX-RS routes, the exception mappers (every 4xx, the
                    security layer's 401 and 403 too, has the body {"message": "..."}), and the
                    native-image reflection registration for what Jackson binds.

    service/src/main/webui/  the client — qits-configuration-platform-frontend, a git submodule. Quinoa
                             builds it during `package` and serves it at / on this service's own
                             host.

## The client

`service/src/main/webui` is the
[qits-configuration-platform-frontend](https://github.com/QuicklyIterateTheSoftware/qits-configuration-platform-frontend)
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
platform's own npm registry, which no `RUN` in a docker build can reach; the pipelines under
`.config/qits/` (`ci-event-release-request.yml` at a release-request fold, `ci-event-release.yml` at
a release) build it in the step container, and `docker/Dockerfile` neuters Quinoa's install and build commands
and guards the staged bundle before the native compile.

So a **clone-alone build now means clone AND `git submodule update --init`, with a node on PATH**:
`verify` runs `package`, and `package` needs both. `./mvnw test` still needs neither, because Quinoa
is off in test mode.
