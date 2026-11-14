package com.magnaboy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import static com.magnaboy.Util.getRandomItem;
import com.magnaboy.scripting.ScriptLoader;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns every loaded region, the entities built from them and the script cache.
 * <p>
 * Threading: the maps are read and written only on the client thread. The one tolerated exception until the dev
 * editor moves out of {@code src/main} is the editor's Swing handlers (the "DEVELOPMENT SECTION" below).
 */
@Slf4j
@Singleton
public class CitizenRegistry {
	private static final float VALID_REGION_VERSION = 0.8f;
	private static final String REGIONDATA_DIRECTORY = new File("src/main/resources/RegionData/").getAbsolutePath();

	private final CitizensPlugin plugin;
	private final Gson gson;
	private final ScriptLoader scriptLoader;
	private final Map<Integer, CitizenRegion> regions = new HashMap<>();
	private final Map<Integer, CitizenRegion> dirtyRegions = new HashMap<>();
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
			.setObjectToRemove(info.removedObject)
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
		return loadRegion(regionId, false);
	}

	public CitizenRegion loadRegion(int regionId, boolean createIfNotExists) {
		if (regions.containsKey(regionId)) {
			return regions.get(regionId);
		}

		InputStream inputStream = CitizenRegistry.class.getResourceAsStream("/RegionData/" + regionId + ".json");
		if (inputStream == null) {
			// No region file was found.
			// If in development, create one, save it and then try to load it.
			if (plugin.IS_DEVELOPMENT && createIfNotExists) {
				CitizenRegion region = new CitizenRegion();
				region.regionId = regionId;
				region.version = VALID_REGION_VERSION;
				try {
					saveRegion(region);
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
				return loadRegion(regionId, false);
			}
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
			if (plugin.IS_DEVELOPMENT) {
				region.entities.values().forEach(Entity::validate);
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
	 * Client thread only: runs {@code function} for every entity within render distance of the local player.
	 */
	public void forEachActiveEntity(Consumer<Entity> function) {
		for (CitizenRegion r : regions.values()) {
			if (r != null) {
				for (Entity e : r.entities.values()) {
					if (e != null && e.distanceToPlayer() <= Util.MAX_ENTITY_RENDER_DISTANCE) {
						function.accept(e);
					}
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
	 * Client thread only.
	 */
	public void updateAllEntities() {
		for (CitizenRegion region : regions.values()) {
			region.updateEntities();
		}
		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
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
	 * loaded. Ids without a data file are remembered and not looked up again while they stay in the set. Calling it
	 * again with the same set changes nothing. It does not spawn anything; see {@link #updateAllEntities()}.
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
				loadRegion(regionId);
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
		region.executorService.shutdownNow();
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
			r.executorService.shutdownNow();
			r.citizenRoster.clear();
			r.sceneryRoster.clear();
			r.entities.clear();
		}
		regions.clear();
		syncedRegionIds.clear();
		dirtyRegions.clear();
		scriptLoader.clear();
	}

	public boolean isEmpty() {
		return regions.isEmpty();
	}

	// DEVELOPMENT SECTION
	public Citizen spawnCitizenFromPanel(CitizenInfo info) {
		Citizen citizen = loadCitizen(info);
		CitizenRegion region = loadRegion(info.regionId, true);
		region.entities.put(info.uuid, citizen);
		region.citizenRoster.add(info);
		dirtyRegion(region);
		plugin.clientThread.invoke(this::updateAllEntities);
		return citizen;
	}

	public Scenery spawnSceneryFromPanel(SceneryInfo info) {
		Scenery scenery = loadScenery(info);
		CitizenRegion region = loadRegion(info.regionId, true);
		region.entities.put(info.uuid, scenery);
		region.sceneryRoster.add(info);
		dirtyRegion(region);
		plugin.clientThread.invoke(this::updateAllEntities);
		return scenery;
	}

	public void updateEntity(EntityInfo info) {
		if (info.entityType == EntityType.Scenery) {
			CitizenRegion region = regions.get(info.regionId);
			Entity e = region.entities.get(info.uuid);
			Scenery updated = loadScenery((SceneryInfo) info);

			addEntityToRegion(updated, info);
			removeEntityFromRegion(e);
		} else {
			CitizenRegion region = regions.get(info.regionId);
			Entity e = region.entities.get(info.uuid);
			Citizen updated = loadCitizen((CitizenInfo) info);

			addEntityToRegion(updated, info);
			removeEntityFromRegion(e);
		}
	}

	public void dirtyRegion(CitizenRegion region) {
		dirtyRegions.put(region.regionId, region);
	}

	public void clearDirtyRegions() {
		dirtyRegions.clear();
	}

	public void addEntityToRegion(Entity e, EntityInfo info) {
		CitizenRegion region = regions.get(e.regionId);
		region.entities.put(e.uuid, e);
		if (info instanceof CitizenInfo) {
			region.citizenRoster.add((CitizenInfo) info);
		}
		if (info instanceof SceneryInfo) {
			region.sceneryRoster.add((SceneryInfo) info);
		}
	}

	private void removeEntityFromRegion(Citizen citizen, CitizenRegion region) {
		CitizenInfo info = region.citizenRoster.stream()
			.filter(c -> Objects.equals(c.uuid, citizen.uuid))
			.findFirst()
			.orElse(null);
		region.citizenRoster.remove(info);
	}

	private void removeEntityFromRegion(Scenery scenery, CitizenRegion region) {
		SceneryInfo info = region.sceneryRoster.stream()
			.filter(c -> Objects.equals(c.uuid, scenery.uuid))
			.findFirst()
			.orElse(null);
		region.sceneryRoster.remove(info);
	}

	public void removeEntityFromRegion(Entity e) {
		CitizenRegion region = regions.get(e.regionId);
		if (e instanceof Citizen) {
			removeEntityFromRegion((Citizen) e, region);
		}
		if (e instanceof Scenery) {
			removeEntityFromRegion((Scenery) e, region);
		}

		region.entities.remove(e.uuid);
		plugin.clientThread.invoke(() -> {
			e.despawn();
		});
		dirtyRegion(region);
	}

	public int dirtyRegionCount() {
		return dirtyRegions.size();
	}

	public void saveDirtyRegions() {
		for (Map.Entry<Integer, CitizenRegion> region : dirtyRegions.entrySet()) {
			try {
				saveRegion(region.getValue());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		clearDirtyRegions();
	}

	private void saveRegion(CitizenRegion region) throws IOException {
		try {
			Path path = Paths.get(REGIONDATA_DIRECTORY, region.regionId + ".json");
			Writer wr = new BufferedWriter(new FileWriter(path.toString()));
			GsonBuilder gb = gson.newBuilder();
			gb.setPrettyPrinting();
			Gson prettyGson = gb.create();
			prettyGson.toJson(region, wr);
			wr.flush();
			wr.close();
		} catch (IOException e) {
			throw new IOException(e);
		}
	}
}
