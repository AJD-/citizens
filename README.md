# Citizens

This plugin adds 'fake npcs' (citizens) to RuneScape to bring more life and action to the world.

# Status

The plugin was marked unavailable on the Plugin Hub in September 2024. A game update in August 2024 had changed model ids,
and citizens no longer looked right. This fork repaired the region data against the game caches and brought the code up to
current RuneLite APIs and Plugin Hub rules. The original plugin is by Magnaboy and contributors (see `LICENSE`). The
original repository is [gc/citizens](https://github.com/gc/citizens).

# What it does for players

Citizens appear in a few towns. They wander around, say things now and then, and some follow small routines. Right-click a
citizen and choose Examine to read its examine text. Nothing else about a citizen is clickable, and citizens do not affect
gameplay.

# Building and running

You need JDK 11.

```
./gradlew build
```

This compiles the plugin, runs checkstyle and runs the tests.

```
./gradlew run
```

This starts a RuneLite development client with the plugin loaded. Like any client, it needs a Jagex account login. See
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) in RuneLite's wiki.

# Project layout

- `src/main/java`: the plugin that ships.
- `src/main/resources/RegionData/<regionId>.json`: one file per map region, listing its citizens and scenery.
- `src/main/resources/Scripts/*.json`: the routines used by scripted citizens.
- `src/test/java`: the tests and the dev editor.

# Adding or editing citizens (dev editor)

The editor is a second plugin, "Citizens Dev Editor". It lives under `src/test`, so it exists only in the development
client (`./gradlew run`) and is not part of the plugin that ships. It is off by default.

1. Start the development client, find "Citizens Dev Editor" in the plugin list and turn it on. A "Citizens" button appears
   in the side bar. The Citizens plugin itself must also be on.
2. Walk to the place you want. Right-click a tile and choose the menu entry "Citizen Editor" with the target "Select Tile".
   The panel shows the selected position.
3. Fill in the form in the panel (type, name, examine text, model ids, animations and so on). Press "Spawn Entity" to add
   a citizen or scenery object on the selected tile. A wandering citizen also needs a wander area: select two tiles in
   turn and use the "Select BL" and "Select TR" buttons. The tile must be in a region loaded around you, and you must be
   logged in.
4. To change something that exists, right-click it and choose its "Citizen Editor" entry ("Select ..."). The panel shows
   "Editing: ..." and fills the form. Change the form and press "Update Entity", or press "Delete Entity".

   To move an entity, select another tile before you press Update. It must stay within its region.
5. Press "Save Changes". This writes every edited region to its file under `src/main/resources/RegionData`. The path is
   relative to the working directory, so run the client from the repository root (`./gradlew run` does). The panel shows
   the path it saved to, and how many regions have unsaved changes.

Edits stay in the client until you save them, even if you walk out of the region. During the session the editor keeps
showing your edited version. The files the plugin bundles are only picked up again when you start the development client
with `./gradlew run`.

Two things to know:

- Save rewrites the whole region file with the editor's own formatting, so review the diff before you commit.
- The Reload button ("Reload All Entities") discards unsaved edits. It needs you to be logged in.

Fields the form has no control for (scripts, merged objects) are kept as they are when you update an
entity. To change a script, edit its JSON file by hand.

# Data checks

```
./gradlew test
```

The tests load every region and script file and check them: ids, enum names, wander boxes, and that every referenced
script exists. `./gradlew build` runs them too. When a check fails, read the message. It names the file and the entity.

# When a game update breaks models or animations

After a game update, citizens may have the wrong or missing body parts, or broken animations. A maintainer tool on the
`cache-diff` branch of this repository (`tools/cache-diff`, with its own README) compares an old and a new game cache and
proposes replacement ids. It can update the region files for you. Its proposals are evidence, not proof, so check the
result in game.

# Guidelines For Adding Citizens

1. Citizens should 'fit in' with runescape, and look natural for the area that they're in (e.g. no zalcano standing in
   varrock square).
2. Currently, we do not add "fake players" (e.g. like "CowSlayer37" wearing dharoks armor).
3. Citizens should not interfere with normal npcs/objects or gameplay.
4. Citizens should be reasonably thought out and unique, and feel like they have a purpose/lore behind them. (e.g. don't
   just add a bunch quickly in random places with no
   thought.)
5. There should be no lag or resource issues as a result of the plugin/citizens.

# Citizen Types

- Scenery; plain objects (e.g. a pot of flowers)
- Stationary Citizens; any citizen which does not ever move tiles.
- Wandering Citizens; a citizen which wanders randomly around a defined box area.
- Scripted Citizens; a citizen which has a set script to follow on a loop (e.g. walk here, do something, walk back)

# Licence

BSD 2-Clause. See `LICENSE`.

# Thanks

- [Diabolickal](https://github.com/Diabolickal) for significant code contributions, including the ingame editor.
- [RuneMonk EntityViewer](https://runemonk.com/tools/entityviewer/?type=item&id=6169#/?type=npc&id=2205&anim=6966&rotation=-1.25_0.21_0&translation=0_-100_-185)
  is very useful for finding models and animations.
