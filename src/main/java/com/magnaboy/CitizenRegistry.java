package com.magnaboy;

import com.google.gson.Gson;
import static com.magnaboy.Util.getRandomItem;
import com.magnaboy.scripting.ScriptLoader;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every loaded region, the entities built from them and the script cache.
 * <p>
 * Concurrency: the maps and rosters are read and written only on the client thread.
 */
@Slf4j
@Singleton
public class CitizenRegistry {
	private static final float VALID_REGION_VERSION = 0.8f;

	private final CitizensPlugin plugin;
	private final Gson gson;
	private final ScriptLoader scriptLoader;
	private final Map<Integer, CitizenRegion> regions = new HashMap<>();
	/**
	 * The region ids last passed to {@link #syncRegions(Set)}, including those without a data file, so a miss is not
	 * retried while it stays in the loaded map.
	 */
	private final Set<Integer> syncedRegionIds = new HashSet<>();

	@Inject
	CitizenRegistry(CitizensPlugin plugin, Gson gson) {
		this.plugin = plugin;
		this.gson = gson;
		this.scriptLoader = new ScriptLoader(gson);
	}

	private static void initCitizenInfo(Citizen citizen, CitizenInfo info) {
		// Citizen
		citizen.setName(info.name)
			.setExamine(info.examineText)
			.setRemarks(info.remarks);

		// Entity
		citizen.setModelIDs(info.modelIds)
			.setModelRecolors(info.modelRecolorFind, info.modelRecolorReplace)
			.setIdleAnimation(info.idleAnimation)
			.setScale(info.scale)
			.setTranslate(info.translate)
			.setBaseOrientation(info.baseOrientation)
			.setUUID(info.uuid)
			.setWorldLocation(info.worldLocation)
			.setRegion(info.regionId);
	}

	public CitizenRegion getRegion(int regionId) {
		return regions.get(regionId);
	}

	/**
	 * An unmodifiable view of the loaded regions.
	 */
	public Collection<CitizenRegion> getRegions() {
		return Collections.unmodifiableCollection(regions.values());
	}

	public CitizenRegion loadRegion(int regionId) {
		if (regions.containsKey(regionId)) {
			return regions.get(regionId);
		}

		InputStream inputStream = CitizenRegistry.class.getResourceAsStream("/RegionData/" + regionId + ".json");
		if (inputStream == null) {
			// No region file was found.
			return null;
		}

		try (Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
			CitizenRegion region = gson.fromJson(reader, CitizenRegion.class);
			if (region == null) {
				return null;
			}
			if (region.version != VALID_REGION_VERSION) {
				return null;
			}
			for (CitizenInfo cInfo : region.citizenRoster) {
				Citizen citizen = loadCitizen(cInfo);
				if (citizen != null) {
					region.entities.put(citizen.uuid, citizen);
				}
			}
			for (SceneryInfo sInfo : region.sceneryRoster) {
				Scenery scenery = loadScenery(sInfo);
				region.entities.put(scenery.uuid, scenery);
			}
			regions.put(regionId, region);
			return region;
		} catch (IOException e) {
			return null;
		}
	}

	public Citizen loadCitizen(CitizenInfo info) {
		Citizen citizen;

		switch (info.entityType) {
			case WanderingCitizen:
				citizen = loadWanderingCitizen(info);
				break;
			case ScriptedCitizen:
				citizen = loadScriptedCitizen(info);
				break;
			default:
				citizen = loadStationaryCitizen(info);
				break;
		}

		if (info.mergedObjects != null) {
			info.mergedObjects.forEach(citizen::addMergedObject);
		}

		if (info.moveAnimation != null) {
			citizen.movingAnimationId = info.moveAnimation;
		}

		return citizen;
	}

	private StationaryCitizen loadStationaryCitizen(CitizenInfo info) {
		info.entityType = EntityType.StationaryCitizen;
		StationaryCitizen citizen = new StationaryCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWorldLocation(info.worldLocation);
		return citizen;
	}

	private WanderingCitizen loadWanderingCitizen(CitizenInfo info) {
		info.entityType = EntityType.WanderingCitizen;
		WanderingCitizen citizen = new WanderingCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWanderRegionBL(info.wanderBoxBL)
			.setWanderRegionTR(info.wanderBoxTR)
			.setWorldLocation(info.worldLocation)
			.setBoundingBox(info.wanderBoxBL, info.wanderBoxTR)
			.setBaseOrientation(getRandomItem(new CardinalDirection[]{CardinalDirection.North, CardinalDirection.South, CardinalDirection.East, CardinalDirection.West}));
		return citizen;
	}

	private ScriptedCitizen loadScriptedCitizen(CitizenInfo info) {
		info.entityType = EntityType.ScriptedCitizen;
		ScriptedCitizen citizen = new ScriptedCitizen(plugin);
		initCitizenInfo(citizen, info);
		citizen.setWorldLocation(info.worldLocation)
			.setScript(scriptLoader.loadScript(info.startScript));

		citizen.baseLocation = info.worldLocation;
		return citizen;
	}

	public Scenery loadScenery(SceneryInfo info) {
		Scenery scenery = new Scenery(plugin).setModelIDs(info.modelIds)
			.setModelRecolors(info.modelRecolorFind, info.modelRecolorReplace)
			.setIdleAnimation(info.idleAnimation)
			.setScale(info.scale)
			.setTranslate(info.translate)
			.setBaseOrientation(info.baseOrientation)
			.setUUID(info.uuid)
			.setWorldLocation(info.worldLocation)
			.setRegion(info.regionId);

		if (info.mergedObjects != null) {
			info.mergedObjects.forEach(scenery::addMergedObject);
		}

		return scenery;
	}

	/**
	 * Client thread only: runs {@code function} for every entity within render distance of the local player, except
	 * those that have failed. See {@code runIsolated}.
	 */
	public void forEachActiveEntity(Consumer<Entity> function) {
		for (CitizenRegion r : regions.values()) {
			if (r != null) {
				for (Entity e : r.entities.values()) {
					runIsolated(e, entity -> {
						if (entity.distanceToPlayer() <= Util.MAX_ENTITY_RENDER_DISTANCE) {
							function.accept(entity);
						}
					});
				}
			}
		}
	}

	public void forEachEntity(Consumer<Entity> function) {
		regions.forEach((regionId, r) -> {
			if (r != null) {
				r.entities.forEach((id, e) -> {
					if (e != null) {
						function.accept(e);
					}
				});
			}
		});
	}

	/**
	 * Client thread only. Spawns or despawns every entity that has not failed. See
	 * {@code runIsolated}.
	 */
	public void updateAllEntities() {
		for (CitizenRegion region : regions.values()) {
			for (Entity entity : region.entities.values()) {
				runIsolated(entity, Entity::update);
			}
		}
	}

	/**
	 * Client thread only. Runs {@code action} for {@code entity} unless it is null or has failed. If the action throws,
	 * the entity is logged, despawned and marked failed, so that every loop skips it until its region is next loaded,
	 * and the caller's loop goes on with the next entity.
	 */
	private void runIsolated(Entity entity, Consumer<Entity> action) {
		if (entity == null || entity.isFailed()) {
			return;
		}
		try {
			action.accept(entity);
		} catch (RuntimeException e) {
			entity.markFailed();
			log.warn("Disabled {} {} ({}) after an error; it stays despawned until its region is loaded again",
				entity.getEntityType(), entity.uuid, entity.name, e);
			despawnSafely(entity);
		}
	}

	/**
	 * Client thread only. Despawns every entity in every region, removing its RuneLiteObject from the scene. The
	 * regions stay loaded. An entity that fails to despawn is logged and does not stop the others.
	 */
	public void despawnAll() {
		forEachEntity(CitizenRegistry::despawnSafely);
	}

	/**
	 * Client thread only. Brings the loaded regions in line with {@code loadedRegionIds}: every region that is not
	 * in it is despawned and unloaded, and every id in it that is not loaded and was not in the previous call is
	 * loaded. Ids without a data file, or whose data fails to load, are remembered and not looked up again while they
	 * stay in the set. Calling it again with the same set changes nothing. It does not spawn anything; see
	 * {@link #updateAllEntities()}.
	 */
	public void syncRegions(Set<Integer> loadedRegionIds) {
		List<Integer> leaving = new ArrayList<>();
		for (Integer regionId : regions.keySet()) {
			if (!loadedRegionIds.contains(regionId)) {
				leaving.add(regionId);
			}
		}
		for (Integer regionId : leaving) {
			unloadRegion(regionId);
		}

		for (Integer regionId : loadedRegionIds) {
			if (!syncedRegionIds.contains(regionId) && !regions.containsKey(regionId)) {
				loadRegionSafely(regionId);
			}
		}

		syncedRegionIds.clear();
		syncedRegionIds.addAll(loadedRegionIds);
	}

	/**
	 * Whether {@code loadedRegionIds} is the set the regions were last synced with.
	 */
	public boolean isSyncedWith(Set<Integer> loadedRegionIds) {
		return syncedRegionIds.equals(loadedRegionIds);
	}

	/**
	 * Client thread only. Despawns and unloads every region. The next {@link #syncRegions(Set)} loads from scratch.
	 */
	public void unloadAll() {
		for (Integer regionId : new ArrayList<>(regions.keySet())) {
			unloadRegion(regionId);
		}
		syncedRegionIds.clear();
	}

	/**
	 * Client thread only: despawns every entity (removing its RuneLiteObject from the scene), then empties the
	 * registry.
	 */
	public void cleanUp() {
		despawnAll();
		clear();
	}

	/**
	 * Client thread only. Despawns the region's entities, then removes it from the registry.
	 */
	private void unloadRegion(int regionId) {
		CitizenRegion region = regions.get(regionId);
		if (region == null) {
			return;
		}
		region.entities.values().forEach(CitizenRegistry::despawnSafely);
		regions.remove(regionId);
	}

	/**
	 * Loads a region, treating bad data as a missing file so that one broken region does not stop the others.
	 */
	private void loadRegionSafely(int regionId) {
		try {
			loadRegion(regionId);
		} catch (RuntimeException e) {
			log.warn("Failed to load region {}", regionId, e);
		}
	}

	private static void despawnSafely(Entity entity) {
		try {
			entity.despawn();
		} catch (RuntimeException e) {
			log.warn("Failed to despawn {} {} ({})", entity.getEntityType(), entity.uuid, entity.name, e);
		}
	}

	/**
	 * Empties the registry without touching the client. Only for {@link #cleanUp()}, once every entity is despawned.
	 */
	private void clear() {
		for (CitizenRegion r : regions.values()) {
			r.citizenRoster.clear();
			r.sceneryRoster.clear();
			r.entities.clear();
		}
		regions.clear();
		syncedRegionIds.clear();
		scriptLoader.clear();
	}

	public boolean isEmpty() {
		return regions.isEmpty();
	}

	// In-memory editing, used by the contributor editor (a separate dev-only plugin in the same package). None of
	// these spawn anything; the next updateAllEntities() does.

	/**
	 * Client thread only. The roster entry for an entity, or null when its region is not loaded or has no such entry.
	 */
	EntityInfo getEntityInfo(int regionId, UUID uuid) {
		assert plugin.client.isClientThread();

		CitizenRegion region = regions.get(regionId);
		if (region == null) {
			return null;
		}
		for (CitizenInfo info : region.citizenRoster) {
			if (Objects.equals(info.uuid, uuid)) {
				return info;
			}
		}
		for (SceneryInfo info : region.sceneryRoster) {
			if (Objects.equals(info.uuid, uuid)) {
				return info;
			}
		}
		return null;
	}

	/**
	 * Client thread only. Adds an empty region for {@code regionId}, in memory only, and returns it. Returns the loaded
	 * region instead if there is one.
	 */
	CitizenRegion createRegion(int regionId) {
		assert plugin.client.isClientThread();

		CitizenRegion region = regions.get(regionId);
		if (region != null) {
			return region;
		}
		region = new CitizenRegion();
		region.regionId = regionId;
		region.version = VALID_REGION_VERSION;
		regions.put(regionId, region);
		return region;
	}

	/**
	 * Client thread only. Builds an entity from {@code info} and adds it, and {@code info} at the end of its roster, to
	 * the loaded region {@code info.regionId}.
	 *
	 * @return the new entity, or null if that region is not loaded
	 */
	Entity addEntity(EntityInfo info) {
		assert plugin.client.isClientThread();

		CitizenRegion region = regions.get(info.regionId);
		if (region == null) {
			return null;
		}
		Entity entity = buildEntity(info);
		region.entities.put(info.uuid, entity);
		addToRoster(region, info);
		return entity;
	}

	/**
	 * Client thread only. Replaces the entity {@code info.uuid} of the loaded region {@code info.regionId} with one built
	 * from {@code info}. The roster entry is replaced where it stands, so the roster order is kept. The old entity is
	 * despawned.
	 *
	 * @return the new entity, or null if the region or the entity is not loaded
	 */
	Entity updateEntity(EntityInfo info) {
		assert plugin.client.isClientThread();

		CitizenRegion region = regions.get(info.regionId);
		Entity old = region == null ? null : region.entities.get(info.uuid);
		if (old == null) {
			return null;
		}
		Entity updated = buildEntity(info);
		if (!replaceInRoster(region, info)) {
			removeFromRosters(region, info.uuid);
			addToRoster(region, info);
		}
		region.entities.put(info.uuid, updated);
		despawnSafely(old);
		return updated;
	}

	/**
	 * Client thread only. Despawns the entity and removes it and its roster entry from its region.
	 *
	 * @return whether its region was loaded
	 */
	boolean removeEntity(Entity entity) {
		assert plugin.client.isClientThread();

		despawnSafely(entity);
		CitizenRegion region = regions.get(entity.regionId);
		if (region == null) {
			return false;
		}
		region.entities.remove(entity.uuid);
		removeFromRosters(region, entity.uuid);
		return true;
	}

	/**
	 * Client thread only. Replaces everything in the loaded region {@code regionId} with entities built from the given
	 * rosters, which are copied. The old entities are despawned. If an entity cannot be built, the region is left as it
	 * was.
	 *
	 * @return whether the region was loaded
	 */
	boolean replaceRegionContents(int regionId, List<CitizenInfo> citizenRoster, List<SceneryInfo> sceneryRoster) {
		assert plugin.client.isClientThread();

		CitizenRegion region = regions.get(regionId);
		if (region == null) {
			return false;
		}
		List<CitizenInfo> citizens = new ArrayList<>(citizenRoster);
		List<SceneryInfo> scenery = new ArrayList<>(sceneryRoster);
		Map<UUID, Entity> entities = new HashMap<>();
		for (CitizenInfo info : citizens) {
			entities.put(info.uuid, loadCitizen(info));
		}
		for (SceneryInfo info : scenery) {
			entities.put(info.uuid, loadScenery(info));
		}

		region.entities.values().forEach(CitizenRegistry::despawnSafely);
		region.entities.clear();
		region.entities.putAll(entities);
		region.citizenRoster.clear();
		region.citizenRoster.addAll(citizens);
		region.sceneryRoster.clear();
		region.sceneryRoster.addAll(scenery);
		return true;
	}

	private Entity buildEntity(EntityInfo info) {
		if (info instanceof CitizenInfo) {
			return loadCitizen((CitizenInfo) info);
		}
		return loadScenery((SceneryInfo) info);
	}

	private static void addToRoster(CitizenRegion region, EntityInfo info) {
		if (info instanceof CitizenInfo) {
			region.citizenRoster.add((CitizenInfo) info);
		} else {
			region.sceneryRoster.add((SceneryInfo) info);
		}
	}

	private static boolean replaceInRoster(CitizenRegion region, EntityInfo info) {
		if (info instanceof CitizenInfo) {
			return replaceInRoster(region.citizenRoster, (CitizenInfo) info);
		}
		return replaceInRoster(region.sceneryRoster, (SceneryInfo) info);
	}

	private static <I extends EntityInfo> boolean replaceInRoster(List<I> roster, I info) {
		for (int i = 0; i < roster.size(); i++) {
			if (Objects.equals(roster.get(i).uuid, info.uuid)) {
				roster.set(i, info);
				return true;
			}
		}
		return false;
	}

	private static void removeFromRosters(CitizenRegion region, UUID uuid) {
		region.citizenRoster.removeIf(info -> Objects.equals(info.uuid, uuid));
		region.sceneryRoster.removeIf(info -> Objects.equals(info.uuid, uuid));
	}
}
