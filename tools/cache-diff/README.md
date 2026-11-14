# cache-diff: model and animation ids after a game update

Citizens are built from game model ids (`modelIds` in `src/main/resources/RegionData/*.json`) and
animation ids (`src/main/java/com/magnaboy/AnimationID.java`). When a game update replaces or renumbers
models, citizens turn up with missing body parts, wrong clothes or broken animations. This tool compares
an **old** game cache (the one the data was last validated against) with a **new** one, and reports
which ids the plugin uses have changed. For each changed id it proposes a replacement, with the evidence
behind it. It can also edit the region data for you.

It is a maintainer tool. It is not part of the plugin build, never runs inside RuneLite and ships
nothing to players.

Run it after a game update, when citizens look wrong in-game.

## Requirements

- JDK 11 (`JAVA_HOME` pointing at it; the repository's Gradle cannot run on newer defaults such as 25).
- Two game caches on disk (see below). Every command runs from the repository root:

```
./gradlew -p tools/cache-diff run --args="<command> <options>"
```

Gradle runs the tool with the repository root as working directory, so relative paths in `--args`
(including the defaults) are relative to the repository root. On Windows use `gradlew.bat` or Git Bash.

## Getting the caches

Caches are large and they are game assets. **Never commit them.** The ignored scratch locations are
`tools/cache-diff/caches/` (caches) and `tools/cache-diff/out/` (generated maps); use those.

- **New (current) cache**: RuneLite's local cache at `~/.runelite/jagexcache/oldschool/LIVE`.
  **Copy it to a scratch directory first**, e.g. `tools/cache-diff/caches/new`, and point the tool at
  the copy. **Never point the tool at the live directory while a client is running.** The client
  writes to it.
- **Old cache**: the cache the region data was last validated against (see "Data baseline" below).
  Historical caches are in the OpenRS2 archive, <https://archive.openrs2.org/>. Find the old-school
  live cache for the date you need, download its cache archive and unzip it, e.g. into
  `tools/cache-diff/caches/old`. Point `--old` at the directory that contains
  `main_file_cache.dat2` and the `main_file_cache.idx*` files.

The tool only reads caches. It refuses a directory without `main_file_cache.dat2` and
`main_file_cache.idx255`.

## Workflow

1. **Report.**
   ```
   ./gradlew -p tools/cache-diff run --args="models --old tools/cache-diff/caches/old --new tools/cache-diff/caches/new --out tools/cache-diff/out/map.json"
   ./gradlew -p tools/cache-diff run --args="anims --old tools/cache-diff/caches/old --new tools/cache-diff/caches/new"
   ```
2. **Review the report** (statuses below). Decide what to do about every `unresolved`,
   `missing-in-new` and `not-in-old` id, and look hard at `kit-only` and `votes-only` proposals.
   Remove any entry you do not accept from `map.json` by hand.
3. **Apply the map.** Run a dry run first; it prints a per-file summary and the edited lines before and after:
   ```
   ./gradlew -p tools/cache-diff run --args="apply --map tools/cache-diff/out/map.json"
   ./gradlew -p tools/cache-diff run --args="apply --map tools/cache-diff/out/map.json --write"
   ```
   Animation proposals are not applied by the tool: edit `AnimationID.java` by hand.
4. **Build**: `./gradlew build` runs the plugin's data-integrity test.
5. **Check the affected citizens in-game.** Proposals are evidence, not proof; only eyes confirm that a
   model looks right.
6. Update the "Data baseline" line below to the date of the new cache.

## Commands

### `models --old <cacheDir> --new <cacheDir> [--data <RegionData dir>] [--out <file>]`

`--data` defaults to `src/main/resources/RegionData`. For every model id used by a citizen (an entity in
`citizenRoster` whose `entityType` is not `Scenery`) it prints:

- the id, its status, the proposed new id, the rule that produced the proposal, how many citizen
  references use it, and the evidence. The evidence covers kit mappings (`kit 26 -> 26632`), NPC votes
  (`votes 26632 x358`, where `same` means the id stayed), how many NPCs used the id in the old and new
  caches, and exact geometry matches.
  `identical` ids are listed together after the table.
- totals by status, and proposals by rule.

It then lists:

- **Scenery and merged-object model ids**: `identical` or `changed` only. These are object models, so
  the tool makes no proposals for them. `changed` comes with a reason: the geometry or colours differ,
  the model is absent from a cache, or the old model is undecodable.
- **Recolour checks** (after applying the proposals): `modelRecolorFind` colours that **no longer**
  exist on any of the citizen's models, meaning the update lost them. Then, separately, colours not
  found on the models in either cache. Those were already ineffective before the update, or the old
  model was undecodable, so they are not caused by the update.

With `--out map.json` it also writes:

- `map.json`: a flat `{ "oldId": newId, ... }` object with **only** the proposals. This is the input to
  `apply`.
- `map.evidence.json` (next to it, `.json` replaced by `.evidence.json`): one entry per proposal with
  `to`, `status`, `rule`, `votes`, `totalVotes`, `kits`, `npcUsersOld`, `npcUsersNew` and
  `geometryMatches`. `apply` never reads it. It is there so the weaker proposals stay visible.

### `anims --old <cacheDir> --new <cacheDir> [--enum <AnimationID.java>]`

`--enum` defaults to `src/main/java/com/magnaboy/AnimationID.java`. For every constant it prints
`unchanged` or `changed`. For `changed` constants it also prints the differences (frame count, frame
lengths, duration, skeleton, maya animation length), the votes, and a proposal if there is one.

- A proposal comes from NPCs that exist in both caches whose stand (or walk) animation was the old id.
  Each such NPC votes for its new stand (or walk) animation. The top target is proposed when it is a
  different id and holds more than half of the votes.
- `changed` without a proposal usually means the animation was converted in place (NPCs still use the
  id), or no NPC uses it. Check those in-game.

Frame-archive ids are deliberately not compared, because cache repacks renumber them without changing
the animation. This command only reports; edit the enum by hand.

### `apply --map <file> [--data <RegionData dir>] [--write]`

Rewrites the `modelIds` arrays of citizen entities using the map. It is a dry run unless you pass
`--write`.

- Only the digits of replaced numbers change. Whitespace, key order, line endings, scenery entities,
  `mergedObjects` and recolour arrays stay byte for byte as they were.
- Each original number is mapped **once per run**. Chains are safe within a run: with `271 -> 28337`
  and `28337 -> 28512` in the map, 271 becomes 28337, not 28512, and an existing 28337 becomes 28512.
- **Run `--write` only once per map.** A second `--write` would turn that new 28337 into 28512. `apply`
  prints a note when the map chains. After a `--write`, make any new map from the edited data instead of
  re-applying the old one.
- Map entries that no citizen uses are listed at the end.
- Nothing is written if any file fails to parse.

Exit codes: `0` success, `2` bad arguments, `1` other errors (unreadable cache, malformed data or map).
Errors are a one-line message on stderr; `help` prints the usage. Under `gradle run`, Gradle adds its own
"Build failed" lines after a non-zero exit.

## Reading the statuses

| status | meaning | proposal |
|---|---|---|
| `identical` | Same geometry and face colours at the same id. Never remapped, whatever the other evidence says. | none |
| `moved` | The old geometry now lives at exactly one other id, which did not already have it in the old cache: that id was absent there, or held a different model. An old model that cannot be decoded does not count. | that id (`geometry-move`) |
| `replaced` | Evidence points to a new id (see the rules below). | that id |
| `changed-in-place` | The model was remade under the same id: a player kit maps the id to itself, or more than half of the NPCs that used it keep it at the same position. | none (keep the id) |
| `missing-in-new` | The id is gone from the new cache and nothing points to a replacement. | none: needs a manual fix |
| `not-in-old` | The tool cannot judge this id: it is absent from the old cache, or the old model is **undecodable** (the evidence says which). Several models of the 2023 OpenRS2 cache cannot be decoded by RuneLite's cache library. | none: needs an in-game look |
| `unresolved` | The id changed but the evidence is missing or contradictory: votes split, kits and votes disagree, ambiguous geometry, or NPCs changed their model lists so much that nothing can be compared. | none: needs a manual decision |

Rules for `replaced`, from strongest to weakest:

- **`kit+votes`**: a player kit lists the old id in the old cache and the new id at the same position in
  the new cache, and the top NPC vote is the same new id.
- **`kit-only`**: the kit mapping as above, with no NPC votes at all (every NPC using it changed the
  length of its model list).
- **`votes-only`**: no kit evidence, and no NPC in the new cache uses the old id any more. At least 3
  NPCs must vote, and one target must hold at least 90% of the votes. (These thresholds are
  `VOTES_ONLY_MIN_VOTERS` and `VOTES_ONLY_MIN_PERCENT` in `ModelMatcher`.)

A **vote** is one position in the model list of an NPC that exists in both caches with a model list of
the same length: the old model at that position votes for the new model at that position. NPCs whose
list changed length give no votes, because positions can no longer be compared.

## Limits

- Proposals are evidence-based, not proof. Check every affected citizen in-game, especially for
  `kit-only` and `votes-only` proposals.
- Scenery and merged-object ids get no proposals; replace those by hand.
- The old cache must be the one the data was last validated against. With a different old cache, ids
  that the data already had fixed show up as changes, or real changes are missed.
- The hash covers geometry and face colours, not textures or render priorities. A texture-only change
  reads as `identical`.
- Models that RuneLite's cache library cannot decode cannot be judged (`not-in-old`).

## Data baseline

Region data was last validated against the OSRS cache of **2026-11-13** (previous baseline: 2023-10-18,
OpenRS2 cache id 1576). Whoever applies a new map updates this line.
