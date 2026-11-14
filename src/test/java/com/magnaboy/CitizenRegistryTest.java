package com.magnaboy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magnaboy.serialization.CitizenInfo;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Builds the registry against a mocked client and loads every bundled region through it.
 */
public class CitizenRegistryTest {
	private static final Path REGION_DIR = Paths.get("src", "main", "resources", "RegionData");

	private CitizensPlugin plugin;
	private CitizenRegistry registry;

	@Before
	public void setUp() {
		// despawn() asserts the client thread, and Gradle runs the tests with assertions enabled
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);

		plugin = new CitizensPlugin();
		plugin.client = client;
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

	/**
	 * A region id with no data file.
	 */
	private static int regionIdWithoutData() throws IOException {
		List<Integer> ids = regionIds();
		int id = 1;
		while (ids.contains(id)) {
			id++;
		}
		return id;
	}

	private static Set<Integer> ids(Integer... ids) {
		return new HashSet<>(Arrays.asList(ids));
	}

	/**
	 * Each citizen's entityType as written in the region file. The loaders overwrite {@code info.entityType}, so it
	 * has to be read before loading.
	 */
	private static Map<UUID, String> citizenTypesInFile(int regionId) throws IOException {
		Map<UUID, String> types = new HashMap<>();
		try (Reader reader = Files.newBufferedReader(REGION_DIR.resolve(regionId + ".json"), StandardCharsets.UTF_8)) {
			JsonObject root = RuneLiteAPI.GSON.fromJson(reader, JsonObject.class);
			for (JsonElement element : root.getAsJsonArray("citizenRoster")) {
				JsonObject citizen = element.getAsJsonObject();
				JsonElement type = citizen.get("entityType");
				types.put(UUID.fromString(citizen.get("uuid").getAsString()), type == null ? null : type.getAsString());
			}
		}
		return types;
	}

	/**
	 * The class the loader builds for a citizen of the given file type; anything else becomes a stationary citizen.
	 */
	private static Class<?> citizenClassFor(String fileType) {
		if (EntityType.WanderingCitizen.name().equals(fileType)) {
			return WanderingCitizen.class;
		}
		if (EntityType.ScriptedCitizen.name().equals(fileType)) {
			return ScriptedCitizen.class;
		}
		return StationaryCitizen.class;
	}

	private static EntityType entityTypeFor(Class<?> entityClass) {
		if (entityClass == WanderingCitizen.class) {
			return EntityType.WanderingCitizen;
		}
		if (entityClass == ScriptedCitizen.class) {
			return EntityType.ScriptedCitizen;
		}
		if (entityClass == StationaryCitizen.class) {
			return EntityType.StationaryCitizen;
		}
		return EntityType.Scenery;
	}

	private List<Entity> allEntities() {
		List<Entity> entities = new ArrayList<>();
		registry.forEachEntity(entities::add);
		return entities;
	}

	@Test
	public void loadsEveryRegion() throws IOException {
		List<Integer> ids = regionIds();
		Assert.assertFalse("No region files found in " + REGION_DIR.toAbsolutePath(), ids.isEmpty());

		List<String> failures = new ArrayList<>();
		for (int id : ids) {
			Map<UUID, String> fileTypes = citizenTypesInFile(id);
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
				checkEntity(label, entity, citizenClassFor(fileTypes.get(info.uuid)), info.uuid, info.regionId,
					failures);
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
				checkEntity(label, entity, Scenery.class, info.uuid, info.regionId, failures);
			}
		}

		if (!failures.isEmpty()) {
			Assert.fail(failures.size() + " failure(s)\n  " + String.join("\n  ", failures));
		}
	}

	private static void checkEntity(String label, Entity entity, Class<?> expectedClass, UUID uuid, int regionId,
		List<String> failures) {
		if (entity.getClass() != expectedClass) {
			failures.add(label + ": built as " + entity.getClass().getSimpleName() + ", expected "
				+ expectedClass.getSimpleName());
		}
		EntityType expectedType = entityTypeFor(expectedClass);
		if (entity.getEntityType() != expectedType) {
			failures.add(label + ": type " + entity.getEntityType() + ", expected " + expectedType);
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
	public void cleanUpEmptiesTheRegistry() throws IOException {
		for (int id : regionIds()) {
			registry.loadRegion(id);
		}
		Assert.assertFalse(registry.isEmpty());
		List<CitizenRegion> loaded = new ArrayList<>(registry.getRegions());

		registry.cleanUp();

		Assert.assertTrue(registry.isEmpty());
		Assert.assertTrue(registry.getRegions().isEmpty());
		Assert.assertTrue(allEntities().isEmpty());
		for (CitizenRegion region : loaded) {
			Assert.assertTrue(region.executorService.isShutdown());
		}
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

	@Test
	public void syncLoadsOnlyRegionsWithData() throws IOException {
		List<Integer> ids = regionIds();
		int missing = regionIdWithoutData();
		Set<Integer> loaded = ids(ids.get(0), ids.get(1), missing);

		registry.syncRegions(loaded);

		Assert.assertNotNull(registry.getRegion(ids.get(0)));
		Assert.assertNotNull(registry.getRegion(ids.get(1)));
		Assert.assertNull(registry.getRegion(missing));
		Assert.assertEquals(2, registry.getRegions().size());
		// The miss counts as synced, so the per-tick check does not keep reconciling because of it
		Assert.assertTrue(registry.isSyncedWith(loaded));
		Assert.assertFalse(registry.isSyncedWith(ids(ids.get(0), ids.get(1))));
	}

	@Test
	public void syncUnloadsRegionsThatLeft() throws IOException {
		List<Integer> ids = regionIds();
		int leavingId = ids.get(0);
		int stayingId = ids.get(1);
		registry.syncRegions(ids(leavingId, stayingId));
		CitizenRegion leaving = registry.getRegion(leavingId);
		CitizenRegion staying = registry.getRegion(stayingId);
		List<Entity> leavingEntities = new ArrayList<>(leaving.entities.values());
		Assert.assertFalse(leavingEntities.isEmpty());

		registry.syncRegions(ids(stayingId));

		Assert.assertNull(registry.getRegion(leavingId));
		Assert.assertSame(staying, registry.getRegion(stayingId));
		Assert.assertTrue(leaving.executorService.isShutdown());
		Assert.assertFalse(staying.executorService.isShutdown());
		List<Entity> remaining = allEntities();
		Assert.assertEquals(staying.entities.size(), remaining.size());
		for (Entity entity : leavingEntities) {
			Assert.assertFalse(remaining.contains(entity));
		}
		Assert.assertTrue(registry.isSyncedWith(ids(stayingId)));
	}

	@Test
	public void syncIsIdempotent() throws IOException {
		List<Integer> ids = regionIds();
		Set<Integer> loaded = ids(ids.get(0), ids.get(1), regionIdWithoutData());
		registry.syncRegions(loaded);
		Map<Integer, CitizenRegion> regionsBefore = new HashMap<>();
		for (CitizenRegion region : registry.getRegions()) {
			regionsBefore.put(region.regionId, region);
		}
		Map<Entity, Boolean> entitiesBefore = new IdentityHashMap<>();
		allEntities().forEach(e -> entitiesBefore.put(e, true));

		registry.syncRegions(loaded);

		Assert.assertEquals(regionsBefore.size(), registry.getRegions().size());
		for (CitizenRegion region : registry.getRegions()) {
			Assert.assertSame(regionsBefore.get(region.regionId), region);
		}
		List<Entity> entitiesAfter = allEntities();
		Assert.assertEquals(entitiesBefore.size(), entitiesAfter.size());
		for (Entity entity : entitiesAfter) {
			Assert.assertTrue(entitiesBefore.containsKey(entity));
		}
		Assert.assertTrue(registry.isSyncedWith(loaded));
	}

	@Test
	public void regionLoadedAgainHasFreshEntities() throws IOException {
		int id = regionIds().get(0);
		registry.syncRegions(ids(id));
		CitizenRegion first = registry.getRegion(id);
		Map<UUID, Entity> firstEntities = new HashMap<>(first.entities);
		Assert.assertFalse(firstEntities.isEmpty());

		registry.syncRegions(Collections.emptySet());
		Assert.assertTrue(registry.isEmpty());
		registry.syncRegions(ids(id));

		CitizenRegion second = registry.getRegion(id);
		Assert.assertNotSame(first, second);
		Assert.assertEquals(firstEntities.keySet(), second.entities.keySet());
		for (Map.Entry<UUID, Entity> entry : firstEntities.entrySet()) {
			Assert.assertNotSame(entry.getValue(), second.entities.get(entry.getKey()));
		}
	}

	@Test
	public void unloadAllEmptiesTheRegistryAndForgetsTheSyncedSet() throws IOException {
		List<Integer> ids = regionIds();
		Set<Integer> loaded = ids(ids.get(0), ids.get(1));
		registry.syncRegions(loaded);
		List<CitizenRegion> regions = new ArrayList<>(registry.getRegions());

		registry.unloadAll();

		Assert.assertTrue(registry.isEmpty());
		Assert.assertTrue(allEntities().isEmpty());
		Assert.assertFalse(registry.isSyncedWith(loaded));
		for (CitizenRegion region : regions) {
			Assert.assertTrue(region.executorService.isShutdown());
		}
	}

	@Test
	public void despawnRemovesOnlyAnObjectTheClientHasRegistered() {
		Scenery scenery = new Scenery(plugin).setUUID(UUID.randomUUID());
		scenery.rlObject = mock(RuneLiteObject.class);

		when(scenery.rlObject.isActive()).thenReturn(false);
		scenery.despawn();
		verify(scenery.rlObject, never()).setActive(false);

		// The entity's own flag is false here too: the client's answer decides
		when(scenery.rlObject.isActive()).thenReturn(true);
		scenery.despawn();
		verify(scenery.rlObject).setActive(false);
	}

	@Test
	public void syncTreatsARegionThatFailsToLoadAsAMiss() throws IOException {
		List<Integer> ids = regionIds();
		int broken = ids.get(0);
		int healthy = ids.get(1);
		AtomicInteger brokenAttempts = new AtomicInteger();
		registry = new CitizenRegistry(plugin, RuneLiteAPI.GSON) {
			@Override
			public CitizenRegion loadRegion(int regionId) {
				if (regionId == broken) {
					brokenAttempts.incrementAndGet();
					throw new IllegalStateException("Expected by the test: bad region data");
				}
				return super.loadRegion(regionId);
			}
		};
		Set<Integer> loaded = ids(broken, healthy);

		registry.syncRegions(loaded);

		Assert.assertNull(registry.getRegion(broken));
		Assert.assertNotNull(registry.getRegion(healthy));
		Assert.assertTrue(registry.isSyncedWith(loaded));
		Assert.assertEquals(1, brokenAttempts.get());

		// Not retried while it stays in the loaded map
		registry.syncRegions(loaded);
		Assert.assertEquals(1, brokenAttempts.get());
	}

	@Test
	public void cleanUpDespawnsTheOthersWhenOneFails() throws IOException {
		AtomicInteger despawned = new AtomicInteger();
		addFailingAndCountingEntities(despawned);

		registry.cleanUp();

		Assert.assertEquals(1, despawned.get());
		Assert.assertTrue(registry.isEmpty());
	}

	@Test
	public void unloadDespawnsTheOthersWhenOneFails() throws IOException {
		AtomicInteger despawned = new AtomicInteger();
		addFailingAndCountingEntities(despawned);

		registry.syncRegions(Collections.emptySet());

		Assert.assertEquals(1, despawned.get());
		Assert.assertTrue(registry.isEmpty());
	}

	private void addFailingAndCountingEntities(AtomicInteger despawned) throws IOException {
		int id = regionIds().get(0);
		registry.syncRegions(ids(id));
		CitizenRegion region = registry.getRegion(id);

		Scenery failing = new Scenery(plugin) {
			@Override
			public boolean despawn() {
				throw new IllegalStateException("Expected by the test: despawn failure");
			}
		};
		failing.setUUID(UUID.randomUUID()).setRegion(id);
		Scenery counting = new Scenery(plugin) {
			@Override
			public boolean despawn() {
				despawned.incrementAndGet();
				return super.despawn();
			}
		};
		counting.setUUID(UUID.randomUUID()).setRegion(id);

		region.entities.put(failing.uuid, failing);
		region.entities.put(counting.uuid, counting);
	}
}
