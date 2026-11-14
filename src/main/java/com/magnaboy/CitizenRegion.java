package com.magnaboy;

import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;

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
	public transient ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

	/**
	 * Client thread only.
	 */
	public void updateEntities() {
		entities.values().forEach(Entity::update);
	}

	/**
	 * Client thread only. The callbacks are run later on the client thread.
	 */
	public void runOncePerTimePeriod(int timePeriodSeconds, int callIntervalSeconds, ClientThread clientThread,
		Consumer<Entity> callback) {
		double chance = (double) callIntervalSeconds / timePeriodSeconds;

		for (Entity entity : entities.values()) {
			if (ThreadLocalRandom.current().nextDouble() < chance) {
				int delayMs = (Util.getRandom(0, (callIntervalSeconds / 2) * 1000));
				executorService.schedule(() -> clientThread.invoke(() -> {
					callback.accept(entity);
				}), delayMs, TimeUnit.MILLISECONDS);
			}
		}
	}
}
