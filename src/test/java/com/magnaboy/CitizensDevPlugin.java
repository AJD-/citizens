package com.magnaboy;

import com.google.gson.Gson;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.ImageUtil;

/**
 * The contributor editor for Citizens: a side panel to place, edit, delete and save citizens and scenery, "Citizen
 * Editor" menu entries to select entities and tiles, and a debug overlay. It lives in {@code src/test}, so
 * {@code ./gradlew run} loads it but the Plugin Hub never builds it. It is off by default.
 * <p>
 * Threads: the selection and the dirty regions belong to the client thread, like the registry they point into. Panel
 * listeners run on the Swing EDT and hand plain values to the client thread with {@code clientThread.invoke}; the
 * client thread hands the panel an {@link EditorSnapshot} with {@link SwingUtilities#invokeLater(Runnable)}.
 */
@Slf4j
@PluginDescriptor(
	name = "Citizens Dev Editor",
	description = "Contributor tools for Citizens: place, edit and save citizens and scenery",
	tags = {"citizens", "developer"},
	enabledByDefault = false
)
@PluginDependency(CitizensPlugin.class)
public class CitizensDevPlugin extends Plugin {
	private static final int PANEL_REFRESH_TICKS = 5;
	private static final String EDITOR_OPTION = ColorUtil.wrapWithColorTag("Citizen Editor", Color.cyan);

	@Inject
	Client client;
	@Inject
	ClientThread clientThread;
	@Inject
	Gson gson;
	@Inject
	PluginManager pluginManager;
	@Inject
	ClientToolbar clientToolbar;
	@Inject
	OverlayManager overlayManager;
	@Inject
	CitizensPlugin plugin;
	@Inject
	CitizenRegistry registry;
	@Inject
	CitizensDevOverlay overlay;
	@Inject
	RegionFileWriter regionFileWriter;

	// Swing EDT only
	private CitizenPanel panel;
	private NavigationButton navButton;

	// Client thread only
	private Entity selectedEntity;
	private WorldPoint selectedPosition;
	/**
	 * Regions with unsaved edits, by id. An entry is dropped once the registry no longer holds that region object.
	 */
	private final Map<Integer, CitizenRegion> dirtyRegions = new HashMap<>();
	private int gameTicks;

	@Override
	protected void startUp() {
		panel = new CitizenPanel();
		panel.init(this);

		final BufferedImage icon = ImageUtil.loadImageResource(CitizensDevPlugin.class, "/citizens_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Citizens")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);

		clientThread.invokeLater(() -> {
			publishStatus(false);
		});
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		clientThread.invokeLater(() -> {
			selectedEntity = null;
			selectedPosition = null;
		});
	}

	/**
	 * Client thread only.
	 */
	Entity getSelectedEntity() {
		return selectedEntity;
	}

	/**
	 * Client thread only.
	 */
	WorldPoint getSelectedPosition() {
		return selectedPosition;
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		if (++gameTicks % PANEL_REFRESH_TICKS == 0) {
			publishStatus(false);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		// Queued so that it runs after the Citizens plugin has loaded or unloaded regions for this state
		clientThread.invokeLater(() -> {
			publishStatus(false);
		});
	}

	@Subscribe
	public void onMenuOpened(MenuOpened ignored) {
		final Menu menu = client.getMenu();
		final Point mousePos = client.getMouseCanvasPosition();
		final int[] index = {1};
		final boolean[] selectedUnderMouse = {false};

		// Select/Deselect every entity under the mouse, scenery and nameless ones included
		registry.forEachActiveEntity(entity -> {
			SimplePolygon clickbox;
			try {
				clickbox = entity.getClickbox();
			} catch (IllegalStateException err) {
				return;
			}
			if (clickbox == null || !clickbox.contains(mousePos.getX(), mousePos.getY())) {
				return;
			}
			boolean selected = entity == selectedEntity;
			if (selected) {
				selectedUnderMouse[0] = true;
			}
			addEditorEntry(menu, index[0]++, (selected ? "Deselect" : "Select") + " <col=fffe00>" + entity.name + "</col>",
				() -> toggleSelection(entity));
		});

		// Tile Selection
		final WorldView wv = client.getTopLevelWorldView();
		final Tile selectedSceneTile = wv == null ? null : wv.getSelectedSceneTile();
		if (selectedSceneTile != null) {
			final WorldPoint tileLocation = selectedSceneTile.getWorldLocation();
			final boolean same = tileLocation.equals(selectedPosition);
			addEditorEntry(menu, index[0]++, (same ? "Deselect" : "Select") + " <col=fffe00>Tile</col>", () -> {
				selectedPosition = same ? null : tileLocation;
				publishStatus(false);
			});
		}

		// Entity Deselect (from anywhere)
		if (selectedEntity != null && !selectedUnderMouse[0]) {
			final Entity selected = selectedEntity;
			final String name = selected instanceof Citizen ? selected.name : "Scenery Object";
			addEditorEntry(menu, index[0] - 1, "Deselect <col=fffe00>" + name + "</col>", () -> {
				if (selectedEntity == selected) {
					selectedEntity = null;
				}
				publishStatus(false);
			});
		}
	}

	private static void addEditorEntry(Menu menu, int index, String target, Runnable onClick) {
		menu.createMenuEntry(index)
			.setOption(EDITOR_OPTION)
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.setDeprioritized(true)
			.onClick(e -> onClick.run());
	}

	/**
	 * Client thread only (menu clicks run there).
	 */
	private void toggleSelection(Entity entity) {
		if (selectedEntity == entity) {
			selectedEntity = null;
			publishStatus(false);
		} else {
			selectedEntity = entity;
			// The stored location, not where the citizen is now: an untouched Update must not move it
			EntityInfo stored = registry.getEntityInfo(entity.regionId, entity.uuid);
			selectedPosition = stored != null && stored.worldLocation != null
				? stored.worldLocation : entity.getWorldLocation();
			publishStatus(true);
		}
	}

	// Editor actions. Called by the panel on the Swing EDT; the work runs on the client thread.

	/**
	 * Adds the entity the panel built to the region of its location, loading that region or creating it in memory if
	 * it has no data file, then selects it.
	 */
	void spawnEntity(EntityInfo info) {
		clientThread.invoke(() -> {
			applySpawn(info);
		});
	}

	/**
	 * Replaces the entity {@code info.uuid} of region {@code info.regionId} with what the panel built.
	 */
	void updateEntity(EntityInfo info) {
		clientThread.invoke(() -> {
			applyUpdate(info);
		});
	}

	void deleteEntity(int regionId, UUID uuid) {
		clientThread.invoke(() -> {
			applyDelete(regionId, uuid);
		});
	}

	/**
	 * Writes every region with unsaved edits to {@code src/main/resources/RegionData}.
	 */
	void saveChanges() {
		clientThread.invoke(() -> {
			applySave();
		});
	}

	/**
	 * Discards every unsaved edit and reloads all entities from the bundled region data.
	 */
	void reloadEntities() {
		clientThread.invoke(() -> {
			applyReload();
		});
	}

	/**
	 * Only a successful spawn or update refills the panel's form, so a refused one leaves the contributor's input.
	 */
	private void applySpawn(EntityInfo info) {
		boolean spawned = false;
		if (isCitizensRunning("spawn")) {
			try {
				CitizenRegion region = registry.loadRegion(info.regionId);
				if (region == null) {
					region = registry.createRegion(info.regionId);
				}
				Entity entity = registry.addEntity(info);
				markDirty(region);
				registry.updateAllEntities();
				selectedEntity = entity;
				selectedPosition = null;
				spawned = true;
			} catch (RuntimeException e) {
				log.warn("Could not spawn {} at {}", info.entityType, info.worldLocation, e);
			}
		}
		publishStatus(spawned);
	}

	private void applyUpdate(EntityInfo info) {
		boolean updated = false;
		if (isCitizensRunning("update")) {
			EntityInfo stored = registry.getEntityInfo(info.regionId, info.uuid);
			if (stored == null) {
				log.warn("Could not update {}: it is no longer loaded", info.uuid);
			} else if (info.worldLocation == null || info.worldLocation.getRegionID() != info.regionId) {
				// An entry must stay in the file of the region it stands in
				log.warn("Could not update {}: {} is outside its region {}; delete it and spawn it there instead",
					info.uuid, info.worldLocation, info.regionId);
			} else {
				keepFieldsTheFormDoesNotEdit(stored, info);
				try {
					selectedEntity = registry.updateEntity(info);
					markDirty(registry.getRegion(info.regionId));
					registry.updateAllEntities();
					updated = true;
				} catch (RuntimeException e) {
					log.warn("Could not update {}", info.uuid, e);
				}
			}
		}
		publishStatus(updated);
	}

	/**
	 * The form has no fields for these, so an update keeps the stored values.
	 */
	private static void keepFieldsTheFormDoesNotEdit(EntityInfo stored, EntityInfo edited) {
		edited.removedObject = stored.removedObject;
		edited.mergedObjects = stored.mergedObjects;
		if (stored instanceof CitizenInfo && edited instanceof CitizenInfo) {
			((CitizenInfo) edited).startScript = ((CitizenInfo) stored).startScript;
		}
	}

	private void applyDelete(int regionId, UUID uuid) {
		CitizenRegion region = registry.getRegion(regionId);
		Entity entity = region == null ? null : region.entities.get(uuid);
		if (entity == null) {
			log.warn("Could not delete {}: it is no longer loaded", uuid);
		} else {
			registry.removeEntity(entity);
			markDirty(region);
		}
		if (selectedEntity != null && uuid.equals(selectedEntity.uuid)) {
			selectedEntity = null;
		}
		publishStatus(false);
	}

	private void applySave() {
		pruneDirtyRegions();
		Iterator<CitizenRegion> regions = dirtyRegions.values().iterator();
		while (regions.hasNext()) {
			CitizenRegion region = regions.next();
			try {
				Path file = regionFileWriter.write(region);
				log.debug("Saved region {} to {}", region.regionId, file);
				regions.remove();
			} catch (IOException e) {
				log.warn("Could not save region {}", region.regionId, e);
			}
		}
		publishStatus(false);
	}

	private void applyReload() {
		selectedEntity = null;
		dirtyRegions.clear();
		if (isCitizensRunning("reload")) {
			registry.cleanUp();
			plugin.reconcile();
		}
		publishStatus(false);
	}

	private boolean isCitizensRunning(String action) {
		boolean running = pluginManager.isPluginActive(plugin);
		if (!running) {
			log.warn("Cannot {} while the Citizens plugin is off", action);
		}
		return running;
	}

	private void markDirty(CitizenRegion region) {
		if (region != null) {
			dirtyRegions.put(region.regionId, region);
		}
	}

	/**
	 * Drops the dirty regions the registry no longer holds: they were unloaded (the player left them, or the Citizens
	 * plugin was turned off and emptied their rosters), so saving them could overwrite real data.
	 */
	private void pruneDirtyRegions() {
		dirtyRegions.entrySet().removeIf(entry -> {
			boolean unloaded = registry.getRegion(entry.getKey()) != entry.getValue();
			if (unloaded) {
				log.warn("Discarded the unsaved changes to region {}: it was unloaded before they were saved",
					entry.getKey());
			}
			return unloaded;
		});
	}

	/**
	 * Client thread only. Hands the panel the editor's current state.
	 *
	 * @param selectionChanged whether the panel should fill its form from the selected entity
	 */
	private void publishStatus(boolean selectionChanged) {
		assert client.isClientThread();

		GameState gameState = client.getGameState();
		if (gameState == GameState.LOGIN_SCREEN || gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR) {
			selectedPosition = null;
		}

		EntityInfo selectedInfo = null;
		if (selectedEntity != null) {
			EntityInfo stored = selectedEntity.isActive()
				? registry.getEntityInfo(selectedEntity.regionId, selectedEntity.uuid) : null;
			if (stored == null) {
				// Out of range, unloaded or removed
				selectedEntity = null;
			} else {
				selectedInfo = gson.fromJson(gson.toJsonTree(stored), stored.getClass());
			}
		}
		String selectedLabel = null;
		if (selectedEntity != null) {
			selectedLabel = selectedEntity instanceof Citizen ? selectedEntity.name : "Scenery Object";
		}

		pruneDirtyRegions();
		int[] counts = new int[2];
		registry.forEachEntity(entity -> {
			counts[1]++;
			if (entity.isActive()) {
				counts[0]++;
			}
		});

		EditorSnapshot snapshot = new EditorSnapshot(gameState, counts[0], counts[1], dirtyRegions.size(),
			selectedPosition, selectedLabel, selectedInfo, selectionChanged);
		SwingUtilities.invokeLater(() -> {
			if (panel != null) {
				panel.apply(snapshot);
			}
		});
	}
}
