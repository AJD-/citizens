package com.magnaboy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magnaboy.serialization.SceneryInfo;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The editor's session rules: an edit survives every way the registry can drop or empty its region, Save writes the
 * edited rosters, a region edited this session shows the editor's version when it is loaded again, and only
 * {@link EditedRegions#discardUnsaved()} (Reload) throws edits away.
 */
public class EditedRegionsTest {
	private static final Path REGION_DIR = Paths.get("src", "main", "resources", "RegionData");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private CitizenRegistry registry;
	private EditedRegions edits;
	private RegionFileWriter writer;
	private int regionId;

	@Before
	public void setUp() throws IOException {
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		CitizensPlugin plugin = new CitizensPlugin();
		plugin.client = client;
		registry = new CitizenRegistry(plugin, RuneLiteAPI.GSON);
		edits = new EditedRegions();
		writer = new RegionFileWriter(RuneLiteAPI.GSON, folder.getRoot().toPath());
		regionId = regionWithScenery();
	}

	private static List<Integer> regionIds() throws IOException {
		try (Stream<Path> stream = Files.list(REGION_DIR)) {
			return stream.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.map(name -> Integer.parseInt(name.substring(0, name.length() - ".json".length())))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	private int regionWithScenery() throws IOException {
		for (int id : regionIds()) {
			CitizenRegion region = registry.loadRegion(id);
			if (!region.sceneryRoster.isEmpty() && !region.citizenRoster.isEmpty()) {
				registry.syncRegions(Collections.emptySet());
				return id;
			}
		}
		throw new AssertionError("No region with citizens and scenery");
	}

	private static int regionIdWithoutData() throws IOException {
		List<Integer> ids = regionIds();
		int id = 1;
		while (ids.contains(id)) {
			id++;
		}
		return id;
	}

	private void load(int... ids) {
		registry.syncRegions(new HashSet<>(Arrays.stream(ids).boxed().collect(Collectors.toList())));
	}

	private static List<UUID> rosterOrder(CitizenRegion region) {
		List<UUID> uuids = new ArrayList<>();
		region.citizenRoster.forEach(info -> uuids.add(info.uuid));
		region.sceneryRoster.forEach(info -> uuids.add(info.uuid));
		return uuids;
	}

	/**
	 * The roster uuids of a saved file, citizens then scenery.
	 */
	private List<UUID> savedRosterOrder(int id) throws IOException {
		try (Reader reader = Files.newBufferedReader(folder.getRoot().toPath().resolve(id + ".json"),
			StandardCharsets.UTF_8)) {
			JsonObject root = RuneLiteAPI.GSON.fromJson(reader, JsonObject.class);
			List<UUID> uuids = new ArrayList<>();
			for (JsonElement e : root.getAsJsonArray("citizenRoster")) {
				uuids.add(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
			}
			for (JsonElement e : root.getAsJsonArray("sceneryRoster")) {
				uuids.add(UUID.fromString(e.getAsJsonObject().get("uuid").getAsString()));
			}
			return uuids;
		}
	}

	/**
	 * Adds a copy of the region's first scenery entry under a new uuid, as Spawn does, and records the edit.
	 *
	 * @return the region's roster order after the edit
	 */
	private List<UUID> spawnScenery(CitizenRegion region) {
		SceneryInfo info = RuneLiteAPI.GSON.fromJson(RuneLiteAPI.GSON.toJsonTree(region.sceneryRoster.get(0)),
			SceneryInfo.class);
		info.uuid = UUID.randomUUID();
		info.regionId = region.regionId;
		Assert.assertNotNull(registry.addEntity(info));
		edits.recordEdit(region);
		return rosterOrder(region);
	}

	private void assertSavedAsEdited(int id, List<UUID> edited) throws IOException {
		Assert.assertEquals(Collections.emptyList(), edits.saveAll(writer));
		Assert.assertEquals(0, edits.unsavedCount());
		Assert.assertEquals(edited, savedRosterOrder(id));
	}

	@Test
	public void editSurvivesTheRegionLeavingTheScene() throws IOException {
		load(regionId);
		List<UUID> edited = spawnScenery(registry.getRegion(regionId));

		load();

		Assert.assertNull(registry.getRegion(regionId));
		Assert.assertEquals(1, edits.unsavedCount());
		assertSavedAsEdited(regionId, edited);
	}

	@Test
	public void editSurvivesUnloadAll() throws IOException {
		load(regionId);
		List<UUID> edited = spawnScenery(registry.getRegion(regionId));

		registry.unloadAll();

		assertSavedAsEdited(regionId, edited);
	}

	@Test
	public void editSurvivesCleanUpEmptyingTheRosters() throws IOException {
		load(regionId);
		CitizenRegion region = registry.getRegion(regionId);
		List<UUID> edited = spawnScenery(region);

		registry.cleanUp();

		// The hazard: the registry's own object is emptied
		Assert.assertTrue(region.citizenRoster.isEmpty());
		Assert.assertTrue(region.sceneryRoster.isEmpty());
		assertSavedAsEdited(regionId, edited);
	}

	@Test
	public void createdRegionSurvivesAndIsSaved() throws IOException {
		int created = regionIdWithoutData();
		load(regionId, created);
		CitizenRegion source = registry.getRegion(regionId);
		CitizenRegion region = registry.createRegion(created);
		SceneryInfo info = RuneLiteAPI.GSON.fromJson(RuneLiteAPI.GSON.toJsonTree(source.sceneryRoster.get(0)),
			SceneryInfo.class);
		info.uuid = UUID.randomUUID();
		info.regionId = created;
		registry.addEntity(info);
		edits.recordEdit(region);

		registry.cleanUp();

		assertSavedAsEdited(created, Collections.singletonList(info.uuid));
	}

	@Test
	public void editedVersionIsInstalledWhenTheRegionIsLoadedAgain() {
		load(regionId);
		CitizenRegion first = registry.getRegion(regionId);
		List<UUID> edited = spawnScenery(first);
		load();
		load(regionId);
		CitizenRegion bundled = registry.getRegion(regionId);
		Assert.assertNotSame(first, bundled);
		Assert.assertNotEquals(edited, rosterOrder(bundled));
		List<Entity> bundledEntities = new ArrayList<>(bundled.entities.values());

		Assert.assertTrue(edits.installInto(registry, bundled));

		Assert.assertSame(bundled, registry.getRegion(regionId));
		Assert.assertEquals(edited, rosterOrder(bundled));
		Assert.assertEquals(new HashSet<>(edited), bundled.entities.keySet());
		for (Entity entity : bundledEntities) {
			Assert.assertNotSame(entity, bundled.entities.get(entity.uuid));
		}
		// Already holds it: nothing to do
		Assert.assertFalse(edits.installInto(registry, bundled));
		// Edits made from here on apply to the edited version and are saved with it
		spawnScenery(bundled);
		Assert.assertEquals(edited.size() + 1, rosterOrder(bundled).size());
	}

	@Test
	public void regionNotEditedIsLeftAlone() {
		load(regionId);
		CitizenRegion region = registry.getRegion(regionId);
		List<UUID> before = rosterOrder(region);

		Assert.assertFalse(edits.installInto(registry, region));
		Assert.assertEquals(before, rosterOrder(region));
	}

	@Test
	public void reloadKeepsWhatWasSavedAndDropsTheRest() throws IOException {
		load(regionId);
		List<UUID> savedOrder = spawnScenery(registry.getRegion(regionId));
		Assert.assertEquals(Collections.emptyList(), edits.saveAll(writer));
		spawnScenery(registry.getRegion(regionId));
		Assert.assertEquals(1, edits.unsavedCount());

		// Reload All Entities
		edits.discardUnsaved();
		registry.cleanUp();
		load(regionId);
		CitizenRegion reloaded = registry.getRegion(regionId);
		edits.installInto(registry, reloaded);

		Assert.assertEquals(0, edits.unsavedCount());
		Assert.assertEquals(savedOrder, rosterOrder(edits.versionOf(regionId)));
		Assert.assertEquals(savedOrder, rosterOrder(reloaded));
	}

	@Test
	public void reloadForgetsARegionThatWasNeverSaved() {
		load(regionId);
		CitizenRegion region = registry.getRegion(regionId);
		spawnScenery(region);

		edits.discardUnsaved();

		Assert.assertNull(edits.versionOf(regionId));
		Assert.assertEquals(0, edits.unsavedCount());
		Assert.assertTrue(edits.regionIds().isEmpty());
	}

	@Test
	public void aRegionThatFailsToSaveStaysUnsavedAndTheOthersAreWritten() throws IOException {
		List<Integer> ids = regionIds();
		int blocked = ids.get(0);
		int other = ids.get(1);
		load(blocked, other);
		for (int id : new int[]{blocked, other}) {
			CitizenRegion region = registry.getRegion(id);
			if (region.sceneryRoster.isEmpty()) {
				edits.recordEdit(region);
			} else {
				spawnScenery(region);
			}
		}
		// A non-empty directory where the blocked region's file should go: the move onto it fails
		Path blocker = folder.getRoot().toPath().resolve(blocked + ".json");
		Files.createDirectory(blocker);
		Files.createFile(blocker.resolve("keep"));

		Assert.assertEquals(Collections.singletonList(blocked), edits.saveAll(writer));

		Assert.assertEquals(1, edits.unsavedCount());
		Assert.assertTrue(Files.isRegularFile(folder.getRoot().toPath().resolve(other + ".json")));
		Files.delete(blocker.resolve("keep"));
		Files.delete(blocker);
		Assert.assertEquals(Collections.emptyList(), edits.saveAll(writer));
		Assert.assertEquals(0, edits.unsavedCount());
	}
}
