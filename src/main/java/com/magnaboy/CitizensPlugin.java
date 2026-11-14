package com.magnaboy;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.util.Providers;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(name = "Citizens", description = "Adds citizens to help bring life to the world")
public class CitizensPlugin extends Plugin {
	private static final int REMARK_DISTANCE = 15;

	@Inject
	Client client;
	@Inject
	ClientThread clientThread;
	@Inject
	ChatMessageManager chatMessageManager;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private CitizensOverlay citizensOverlay;
	@Inject
	private CitizenRegistry registry;
	/**
	 * Drives the remark and wander rolls. Only used on the client thread.
	 */
	private final Random random = new Random();
	/**
	 * Game ticks seen since the plugin was created; the clock for the wander cooldown.
	 */
	private int gameTick;

	@Override
	public void configure(Binder binder) {
		binder.bind(CitizenRegistry.class);
	}

	/**
	 * Lets a plugin that declares {@code @PluginDependency(CitizensPlugin.class)} inject this plugin and its registry.
	 * The bindings hand out these existing instances: providers rather than {@code toInstance}, so the dependent
	 * injector neither creates a second registry nor injects this plugin's fields again.
	 */
	@Override
	protected Module getPublicModule() {
		return binder -> {
			binder.bind(CitizensPlugin.class).toProvider(Providers.of(this));
			binder.bind(CitizenRegistry.class).toProvider(Providers.of(registry));
		};
	}

	@Override
	protected void startUp() {
		overlayManager.add(citizensOverlay);

		// Shows citizens straight away when enabled while logged in. Runs after any cleanup task queued by a
		// preceding shutDown(): invokeLater tasks run in FIFO order.
		clientThread.invokeLater(this::reconcile);
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(citizensOverlay);
		// Despawn every entity before anything is cleared, so that every RuneLiteObject leaves the scene
		clientThread.invokeLater(registry::cleanUp);
	}

	/**
	 * Client thread only. Brings the registry in line with the current scene: unloads regions that left the loaded
	 * map, loads the ones that entered it, then spawns or despawns every entity. Does nothing unless logged in with
	 * a top-level world view and a local player. In an instance every region is unloaded. Idempotent.
	 */
	void reconcile() {
		assert client.isClientThread();

		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || client.getLocalPlayer() == null) {
			return;
		}

		registry.syncRegions(loadedRegionIds(wv));
		registry.updateAllEntities();
	}

	/**
	 * The region ids citizens may be loaded for: the scene's map regions, or none in an instance (instances reuse
	 * the region ids of the areas they copy).
	 */
	private static Set<Integer> loadedRegionIds(WorldView wv) {
		Set<Integer> ids = new HashSet<>();
		if (wv.isInstance()) {
			return ids;
		}
		int[] mapRegions = wv.getMapRegions();
		if (mapRegions != null) {
			for (int regionId : mapRegions) {
				ids.add(regionId);
			}
		}
		return ids;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		switch (gameStateChanged.getGameState()) {
			case LOADING:
				// Scene-local coordinates are about to change. Keep the regions, spawn nothing until LOGGED_IN.
				registry.despawnAll();
				break;
			case LOGGED_IN:
				reconcile();
				break;
			case HOPPING:
			case LOGIN_SCREEN:
			case CONNECTION_LOST:
				registry.unloadAll();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event) {
		if (event.getWorldView().isTopLevel()) {
			registry.despawnAll();
		}
	}

	@Subscribe
	public void onWorldViewLoaded(WorldViewLoaded event) {
		if (event.getWorldView().isTopLevel()) {
			// A load without a preceding unload must not leave objects registered against the old scene
			registry.despawnAll();
			reconcile();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		gameTick++;
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) {
			return;
		}
		if (!registry.isSyncedWith(loadedRegionIds(wv))) {
			// Safety net: the scene changed without an event that reconciled it, so every object's scene-local
			// position is stale. Take them all down before reconciling, as LOADING would have.
			registry.despawnAll();
			reconcile();
			return;
		}
		registry.updateAllEntities();
		rollIdleBehaviour();
	}

	/**
	 * Client thread only. Once per game tick, after the spawn/despawn pass: gives each spawned citizen its chance to
	 * make a remark and each wandering citizen its chance to start a wander.
	 */
	private void rollIdleBehaviour() {
		registry.forEachActiveEntity(entity -> {
			if (!entity.isActive() || !entity.isCitizen()) {
				return;
			}
			Citizen citizen = (Citizen) entity;

			if (citizen instanceof WanderingCitizen) {
				WanderingCitizen wanderer = (WanderingCitizen) citizen;
				if (wanderer.getCurrentTarget() == null
					&& BehaviourRolls.rollWander(gameTick, wanderer.getLastWanderTick(), random)) {
					wanderer.wander(gameTick);
				}
			}

			if (citizen.remarks != null && citizen.remarks.length > 0 && citizen.activeRemark == null
				&& citizen.distanceToPlayer() < REMARK_DISTANCE && BehaviourRolls.rollRemark(random)) {
				citizen.sayRandomRemark();
			}
		});
	}

	@Subscribe
	public void onClientTick(ClientTick ignored) {
		registry.forEachActiveEntity((entity) -> {
			if (entity.isActive() && entity.isCitizen()) {
				((Citizen) entity).onClientTick();
			}
		});
	}

	@Subscribe
	public void onMenuOpened(MenuOpened ignored) {
		Point mousePos = client.getMouseCanvasPosition();
		registry.forEachActiveEntity(entity -> {
			if (!entity.isActive() || entity.entityType == EntityType.Scenery || entity.name == null
				|| entity.examine == null) {
				return;
			}
			SimplePolygon clickbox;
			try {
				clickbox = entity.getClickbox();
			} catch (IllegalStateException err) {
				return;
			}
			if (clickbox == null) {
				return;
			}
			if (clickbox.contains(mousePos.getX(), mousePos.getY())) {
				client.getMenu().createMenuEntry(1)
					.setOption("Examine")
					.setTarget("<col=fffe00>" + entity.name + "</col>")
					.setType(MenuAction.RUNELITE)
					.setParam0(0)
					.setParam1(0)
					.setDeprioritized(true)
					.onClick(e -> examine(entity));
			}
		});
	}

	/**
	 * Client thread only (menu clicks run there). Posts the entity's examine text to the chat box.
	 */
	private void examine(Entity entity) {
		String chatMessage = new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append(entity.examine)
			.build();

		chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.NPC_EXAMINE)
			.runeLiteFormattedMessage(chatMessage)
			.timestamp((int) (System.currentTimeMillis() / 1000)).build());
	}
}
