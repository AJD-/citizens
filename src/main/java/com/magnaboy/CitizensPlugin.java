package com.magnaboy;

import com.google.gson.Gson;
import com.google.inject.Binder;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
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
	public static boolean shuttingDown;
	@Inject
	public Client client;
	@Inject
	public ClientThread clientThread;
	public CitizenPanel panel;

	public boolean IS_DEVELOPMENT = false;
	public boolean entitiesAreReady = false;
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

	public boolean isReady() {
		return entitiesAreReady && client.getLocalPlayer() != null;
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

		// Runs after any cleanup task queued by a preceding shutDown(): invokeLater tasks run in FIFO order
		clientThread.invokeLater(() -> {
			if (isReady()) {
				checkRegions();
			}
			registry.updateAllEntities();
		});
		Util.initAnimationData(this);
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(citizensOverlay);
		clientThread.invokeLater(() -> {
			shuttingDown = true;
			// Despawn every entity before anything is cleared, so that every RuneLiteObject leaves the scene
			registry.cleanUp();
			shuttingDown = false;
		});
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
	 * Client thread only.
	 */
	protected void despawnAll() {
		registry.forEachActiveEntity(Entity::despawn);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState newState = gameStateChanged.getGameState();

		if (newState == GameState.LOGGED_IN) {
			checkRegions();
		}

		if (newState == GameState.LOADING) {
			despawnAll();
			registry.updateAllEntities();
		}
	}

	@Schedule(
		period = 3,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void citizenBehaviourTick() {
		clientThread.invoke(() -> {
			if (!isReady()) {
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
		registry.updateAllEntities();
	}

	@Subscribe
	public void onClientTick(ClientTick ignored) {
		registry.forEachActiveEntity((entity) -> {
			if (entity.isCitizen()) {
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

	/**
	 * Client thread only.
	 */
	private void checkRegions() {
		WorldView wv = client.getTopLevelWorldView();
		int[] loaded = wv == null ? null : wv.getMapRegions();
		if (loaded == null) {
			return;
		}
		// Load newly loaded regions; regions already in the registry are kept as they are
		for (int i : loaded) {
			registry.loadRegion(i);
		}
		entitiesAreReady = true;
	}
}
