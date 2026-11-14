package com.magnaboy;

import com.google.gson.Gson;
import com.google.inject.Binder;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(name = "Citizens", description = "Adds citizens to help bring life to the world")
public class CitizensPlugin extends Plugin {
	@Inject
	public Client client;
	@Inject
	public ClientThread clientThread;
	public CitizenPanel panel;

	public boolean IS_DEVELOPMENT = false;
	@Inject
	public Gson gson;
	@Inject
	ChatMessageManager chatMessageManager;
	@Inject
	@Getter
	private CitizensConfig config;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private CitizensOverlay citizensOverlay;
	@Inject
	private CitizenRegistry registry;
	@Inject
	private ClientToolbar clientToolbar;
	private NavigationButton navButton;

	@Provides
	CitizensConfig getConfig(ConfigManager configManager) {
		return configManager.getConfig(CitizensConfig.class);
	}

	@Override
	public void configure(Binder binder) {
		binder.bind(CitizenRegistry.class);
	}

	@Override
	protected void startUp() {
		panel = injector.getInstance(CitizenPanel.class);
		panel.init(this, citizensOverlay);
		overlayManager.add(citizensOverlay);

		// For now, the only thing in the panel is dev stuff
		if (IS_DEVELOPMENT) {
			// Add to sidebar
			final BufferedImage icon = ImageUtil.loadImageResource(CitizensPlugin.class, "/citizens_icon.png");
			navButton = NavigationButton.builder()
				.tooltip("Citizens")
				.icon(icon)
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
		}

		// Before the hand-off below: scripted citizens can start their routine as soon as reconcile spawns them
		Util.initAnimationData(this);

		// Shows citizens straight away when enabled while logged in. Runs after any cleanup task queued by a
		// preceding shutDown(): invokeLater tasks run in FIFO order.
		clientThread.invokeLater(this::reconcile);
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(citizensOverlay);
		// Despawn every entity before anything is cleared, so that every RuneLiteObject leaves the scene
		clientThread.invokeLater(registry::cleanUp);
		if (IS_DEVELOPMENT) {
			panel.cleanup();
			panel.update();
		}
		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
		}
	}

	void reload() {
		shutDown();
		startUp();
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
			reconcile();
		}
	}

	@Schedule(
		period = 3,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void citizenBehaviourTick() {
		clientThread.invoke(() -> {
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) {
				return;
			}

			for (CitizenRegion r : registry.getRegions()) {
				r.runOncePerTimePeriod(10, 3, clientThread, entity -> {
					if (entity instanceof WanderingCitizen && entity.isActive()) {
						((WanderingCitizen) entity).wander();
					}
				});

				r.runOncePerTimePeriod(60, 3, clientThread, entity -> {
					if (entity.isActive() && entity.isCitizen() && entity.distanceToPlayer() < 15) {
						((Citizen) entity).sayRandomRemark();
					}
				});
			}

			panel.update();
		});
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
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
		final int[] firstMenuIndex = {1};

		Point mousePos = client.getMouseCanvasPosition();
		final AtomicBoolean[] clickedCitizen = {new AtomicBoolean(false)};
		registry.forEachActiveEntity(entity -> {
			if (entity.entityType == EntityType.Scenery && !IS_DEVELOPMENT) {
				return;
			}
			if ((entity.name != null && entity.examine != null) || IS_DEVELOPMENT) {
				SimplePolygon clickbox;
				try {
					clickbox = entity.getClickbox();
				} catch (IllegalStateException err) {
					return;
				}
				if (clickbox == null) {
					return;
				}
				boolean doesClickBoxContainMousePos = clickbox.contains(mousePos.getX(), mousePos.getY());
				if (doesClickBoxContainMousePos) {
					if (doesClickBoxContainMousePos) {
						client.createMenuEntry(firstMenuIndex[0])
							.setOption("Examine")
							.setTarget("<col=fffe00>" + entity.name + "</col>")
							.setType(MenuAction.RUNELITE)
							.setParam0(0)
							.setParam1(0)
							.setDeprioritized(true);
					}
				}

				// Select/Deselect
				if (IS_DEVELOPMENT && doesClickBoxContainMousePos) {
					String action = "Select";
					if (CitizenPanel.selectedEntity == entity) {
						action = "Deselect";
						clickedCitizen[0].set(true);
					}

					client.createMenuEntry(firstMenuIndex[0]++)
						.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
						.setTarget(action + " <col=fffe00>" + entity.name + "</col>")
						.setType(MenuAction.RUNELITE)
						.setDeprioritized(true)
						.onClick(e -> {
							panel.setSelectedEntity(entity);
							panel.update();
						});
				}
			}
		});

		if (IS_DEVELOPMENT) {
			// Tile Selection
			final WorldView wv = client.getTopLevelWorldView();
			final Tile selectedSceneTile = wv == null ? null : wv.getSelectedSceneTile();
			if (selectedSceneTile != null) {
				final boolean same = CitizenPanel.selectedPosition != null && CitizenPanel.selectedPosition.equals(selectedSceneTile.getWorldLocation());
				final String action = same ? "Deselect" : "Select";
				client.createMenuEntry(firstMenuIndex[0]++)
					.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
					.setTarget(action + " <col=fffe00>Tile</col>")
					.setType(MenuAction.RUNELITE)
					.setDeprioritized(true)
					.onClick(e -> {
						if (same) {
							CitizenPanel.selectedPosition = null;
						} else {
							CitizenPanel.selectedPosition = selectedSceneTile.getWorldLocation();
						}
						panel.update();
					});
			}
			// Entity Deselect (from anywhere)
			if (CitizenPanel.selectedEntity != null && !clickedCitizen[0].get()) {
				String name = "Scenery Object";
				if (CitizenPanel.selectedEntity instanceof Citizen) {
					name = CitizenPanel.selectedEntity.name;
				}
				client.createMenuEntry(firstMenuIndex[0] - 1)
					.setOption(ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan))
					.setTarget("Deselect <col=fffe00>" + name + "</col>")
					.setType(MenuAction.RUNELITE)
					.setDeprioritized(true)
					.onClick(e -> {
						panel.setSelectedEntity(CitizenPanel.selectedEntity);
						panel.update();
					});
			}
		}
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked event) {
		if (!event.getMenuOption().equals("Examine")) {
			return;
		}
		registry.forEachActiveEntity((entity) -> {
			if (event.getMenuTarget().equals("<col=fffe00>" + entity.name + "</col>")) {
				event.consume();
				String chatMessage = new ChatMessageBuilder()
					.append(ChatColorType.NORMAL)
					.append(entity.examine)
					.build();

				chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.NPC_EXAMINE)
					.runeLiteFormattedMessage(chatMessage)
					.timestamp((int) (System.currentTimeMillis() / 1000)).build());
			}
		});
	}
}
