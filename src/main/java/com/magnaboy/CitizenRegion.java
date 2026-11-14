package com.magnaboy;

import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * One loaded region: the Gson-mapped region file plus the entities built from it. Loading, iteration and the dev
 * editor operations live in {@link CitizenRegistry}.
 */
public class CitizenRegion {
	public transient HashMap<UUID, Entity> entities = new HashMap<>();

	public float version;
	public int regionId;
	public List<CitizenInfo> citizenRoster = new ArrayList<>();
	public List<SceneryInfo> sceneryRoster = new ArrayList<>();

	/**
	 * Client thread only.
	 */
	public void updateEntities() {
		entities.values().forEach(Entity::update);
	}
}
