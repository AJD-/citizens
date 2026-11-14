package com.magnaboy;

import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Builds the registry without a running client and loads every bundled region through it.
 */
public class CitizenRegistryTest {
	private static final Path REGION_DIR = Paths.get("src", "main", "resources", "RegionData");

	private CitizensPlugin plugin;
	private CitizenRegistry registry;

	@Before
	public void setUp() {
		plugin = new CitizensPlugin();
		registry = new CitizenRegistry(plugin, RuneLiteAPI.GSON);
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

	@Test
	public void loadsEveryRegion() throws IOException {
		List<Integer> ids = regionIds();
		Assert.assertFalse("No region files found in " + REGION_DIR.toAbsolutePath(), ids.isEmpty());

		List<String> failures = new ArrayList<>();
		for (int id : ids) {
			CitizenRegion region = registry.loadRegion(id);
			if (region == null) {
				failures.add(id + ": loadRegion returned null");
				continue;
			}
			Assert.assertSame(region, registry.getRegion(id));

			int expected = region.citizenRoster.size() + region.sceneryRoster.size();
			if (region.entities.size() != expected) {
				failures.add(id + ": " + region.entities.size() + " entities for " + expected + " roster entries");
			}

			for (CitizenInfo info : region.citizenRoster) {
				Entity entity = region.entities.get(info.uuid);
				String label = id + " " + info.name + " " + info.uuid;
				if (entity == null) {
					failures.add(label + ": no entity");
					continue;
				}
				checkEntity(label, entity, info.entityType, info.uuid, info.regionId, failures);
				if (entity instanceof ScriptedCitizen && ((ScriptedCitizen) entity).getScript() == null) {
					failures.add(label + ": script '" + info.startScript + "' was not loaded");
				}
			}

			for (SceneryInfo info : region.sceneryRoster) {
				Entity entity = region.entities.get(info.uuid);
				String label = id + " scenery " + info.uuid;
				if (entity == null) {
					failures.add(label + ": no entity");
					continue;
				}
				// Scenery entries may omit entityType; the loader always builds Scenery
				checkEntity(label, entity, EntityType.Scenery, info.uuid, info.regionId, failures);
			}
		}

		if (!failures.isEmpty()) {
			Assert.fail(failures.size() + " failure(s)\n  " + String.join("\n  ", failures));
		}
	}

	private static void checkEntity(String label, Entity entity, EntityType type, UUID uuid, int regionId,
		List<String> failures) {
		if (entity.getEntityType() != type) {
			failures.add(label + ": type " + entity.getEntityType() + ", expected " + type);
		}
		if (!uuid.equals(entity.uuid)) {
			failures.add(label + ": uuid " + entity.uuid);
		}
		if (entity.regionId == null || entity.regionId != regionId) {
			failures.add(label + ": regionId " + entity.regionId + ", expected " + regionId);
		}
		if (entity.isActive()) {
			failures.add(label + ": active straight after loading");
		}
	}

	@Test
	public void loadingTwiceReturnsTheSameRegion() throws IOException {
		int id = regionIds().get(0);
		CitizenRegion first = registry.loadRegion(id);
		Assert.assertNotNull(first);
		Assert.assertSame(first, registry.loadRegion(id));
	}

	@Test
	public void clearEmptiesTheRegistry() throws IOException {
		for (int id : regionIds()) {
			registry.loadRegion(id);
		}
		Assert.assertFalse(registry.isEmpty());

		registry.clear();

		Assert.assertTrue(registry.isEmpty());
		Assert.assertTrue(registry.getRegions().isEmpty());
		List<Entity> remaining = new ArrayList<>();
		registry.forEachEntity(remaining::add);
		Assert.assertTrue(remaining.isEmpty());
	}

	@Test
	public void unknownRegionIsNotLoaded() {
		Assert.assertNull(registry.loadRegion(-1));
		Assert.assertTrue(registry.isEmpty());
	}

	@Test
	public void entityEqualityFollowsUuid() {
		UUID uuid = UUID.randomUUID();
		Scenery a = new Scenery(plugin).setUUID(uuid);
		Scenery b = new Scenery(plugin).setUUID(UUID.fromString(uuid.toString()));
		StationaryCitizen c = new StationaryCitizen(plugin).setUUID(UUID.randomUUID());

		Assert.assertEquals(a, b);
		Assert.assertEquals(a.hashCode(), b.hashCode());
		Assert.assertNotEquals(a, c);
	}
}
