package com.magnaboy;

import com.google.gson.Gson;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Threads: the selection and the edited regions belong to the client thread, like the registry they point into. Panel
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
	private final EditedRegions edits = new EditedRegions();
	/**
	 * The outcome of the last editor action that the panel should show, or null.
	 */
	private String message;
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
		installEditedVersions();
		if (++gameTicks % PANEL_REFRESH_TICKS == 0) {
			publishStatus(false);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		// Queued so that it runs after the Citizens plugin has loaded or unloaded regions for this state
		clientThread.invokeLater(() -> {
			installEditedVersions();
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
	 * Writes every region with unsaved edits to {@code src/main/resources/RegionData}. Works in any state, also while
	 * logged out or while the Citizens plugin is off.
	 */
	void saveChanges() {
		clientThread.invoke(() -> {
			applySave();
		});
	}

	/**
	 * Discards every unsaved edit and reloads all entities: from the bundled region data, or for a region saved this
	 * session from what was saved.
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
			if (!sceneRegionIds().contains(info.regionId)) {
				// A region outside the scene would be unloaded again by the next region sync
				refuse("Cannot spawn: the selected tile is in region " + info.regionId
					+ ", which is not loaded around you. Select a tile nearby.");
			} else {
				try {
					installEditedVersions();
					CitizenRegion region = registry.loadRegion(info.regionId);
					if (region == null) {
						region = registry.createRegion(info.regionId);
					}
					Entity entity = registry.addEntity(info);
					edits.recordEdit(region);
					registry.updateAllEntities();
					selectedEntity = entity;
					selectedPosition = null;
					message = null;
					spawned = true;
				} catch (RuntimeException e) {
					log.warn("Could not spawn {} at {}", info.entityType, info.worldLocation, e);
					message = "Could not spawn: " + e.getMessage();
				}
			}
		}
		publishStatus(spawned);
	}

	private void applyUpdate(EntityInfo info) {
		boolean updated = false;
		if (isCitizensRunning("update")) {
			installEditedVersions();
			EntityInfo stored = registry.getEntityInfo(info.regionId, info.uuid);
			if (stored == null) {
				refuse("Cannot update: the entity is no longer loaded.");
			} else if (info.worldLocation == null || info.worldLocation.getRegionID() != info.regionId) {
				// An entry must stay in the file of the region it stands in
				refuse("Cannot update: the selected tile is outside the entity's region " + info.regionId
					+ ". Delete it and spawn it there instead.");
			} else {
				keepFieldsTheFormDoesNotEdit(stored, info);
				try {
					selectedEntity = registry.updateEntity(info);
					edits.recordEdit(registry.getRegion(info.regionId));
					registry.updateAllEntities();
					message = null;
					updated = true;
				} catch (RuntimeException e) {
					log.warn("Could not update {}", info.uuid, e);
					message = "Could not update: " + e.getMessage();
				}
			}
		}
		publishStatus(updated);
	}

	/**
	 * The form has no fields for these, so an update keeps the stored values.
	 */
	private static void keepFieldsTheFormDoesNotEdit(EntityInfo stored, EntityInfo edited) {
		edited.mergedObjects = stored.mergedObjects;
		if (stored instanceof CitizenInfo && edited instanceof CitizenInfo) {
			((CitizenInfo) edited).startScript = ((CitizenInfo) stored).startScript;
		}
	}

	private void applyDelete(int regionId, UUID uuid) {
		if (isCitizensRunning("delete")) {
			installEditedVersions();
			CitizenRegion region = registry.getRegion(regionId);
			Entity entity = region == null ? null : region.entities.get(uuid);
			if (entity == null) {
				refuse("Cannot delete: the entity is no longer loaded.");
			} else {
				registry.removeEntity(entity);
				edits.recordEdit(region);
				message = null;
			}
		}
		if (selectedEntity != null && uuid.equals(selectedEntity.uuid)) {
			selectedEntity = null;
		}
		publishStatus(false);
	}

	private void applySave() {
		int pending = edits.unsavedCount();
		List<Integer> failed = edits.saveAll(regionFileWriter);
		if (!failed.isEmpty()) {
			message = "Could not save region(s) " + failed + "; they still have unsaved changes. See the log.";
		} else if (pending > 0) {
			message = "Saved " + pending + " region(s) to " + RegionFileWriter.REGION_DATA_DIRECTORY + ".";
		} else {
			message = "Nothing to save.";
		}
		publishStatus(false);
	}

	private void applyReload() {
		if (isCitizensRunning("reload")) {
			selectedEntity = null;
			selectedPosition = null;
			edits.discardUnsaved();
			registry.cleanUp();
			plugin.reconcile();
			installEditedVersions();
			message = null;
		}
		publishStatus(false);
	}

	private boolean isCitizensRunning(String action) {
		boolean running = pluginManager.isPluginActive(plugin);
		if (!running) {
			refuse("Cannot " + action + " while the Citizens plugin is off.");
		}
		return running;
	}

	private void refuse(String reason) {
		log.warn(reason);
		message = reason;
	}

	/**
	 * The region ids loaded around the player, as the Citizens plugin syncs them: none in an instance, and none unless
	 * logged in with a top-level world view.
	 */
	private Set<Integer> sceneRegionIds() {
		Set<Integer> ids = new HashSet<>();
		WorldView wv = client.getTopLevelWorldView();
		if (client.getGameState() != GameState.LOGGED_IN || wv == null || wv.isInstance()) {
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

	/**
	 * Client thread only. Makes every edited region that is loaded around the player show the editor's version, also
	 * when the registry loaded it again from the bundled data or does not hold it (a region created by the editor has
	 * no data file). Does nothing while the Citizens plugin is off.
	 */
	private void installEditedVersions() {
		if (!pluginManager.isPluginActive(plugin)) {
			return;
		}
		Set<Integer> scene = sceneRegionIds();
		boolean changed = false;
		for (int regionId : edits.regionIds()) {
			if (!scene.contains(regionId)) {
				continue;
			}
			CitizenRegion region = registry.getRegion(regionId);
			if (region == null) {
				region = registry.createRegion(regionId);
			}
			changed |= edits.installInto(registry, region);
		}
		if (changed) {
			registry.updateAllEntities();
		}
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

		int[] counts = new int[2];
		registry.forEachEntity(entity -> {
			counts[1]++;
			if (entity.isActive()) {
				counts[0]++;
			}
		});

		EditorSnapshot snapshot = new EditorSnapshot(gameState, counts[0], counts[1], edits.unsavedCount(),
			selectedPosition, selectedLabel, selectedInfo, selectionChanged, message);
		SwingUtilities.invokeLater(() -> {
			if (panel != null) {
				panel.apply(snapshot);
			}
		});
	}
}
