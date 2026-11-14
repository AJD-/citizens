package com.magnaboy;

import com.magnaboy.serialization.EntityInfo;
import lombok.Value;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;

/**
 * What the editor panel shows, taken on the client thread and handed to the Swing EDT. The fields are plain values;
 * {@link #selectedInfo} is a copy made for this snapshot that nothing changes afterwards.
 */
@Value
class EditorSnapshot {
	static final EditorSnapshot EMPTY = new EditorSnapshot(GameState.UNKNOWN, 0, 0, 0, null, null, null, false, null);

	GameState gameState;
	int activeEntities;
	int totalEntities;
	/**
	 * How many regions have unsaved changes, loaded or not.
	 */
	int dirtyRegions;
	/**
	 * The selected tile, or null.
	 */
	WorldPoint selectedPosition;
	/**
	 * How the panel names the selected entity, or null when none is selected.
	 */
	String selectedLabel;
	/**
	 * A copy of the selected entity's roster entry, or null when none is selected.
	 */
	EntityInfo selectedInfo;
	/**
	 * Whether a different entity was selected, so the form should be filled from {@link #selectedInfo}.
	 */
	boolean selectionChanged;
	/**
	 * The outcome of the last editor action (a refusal, a failure or what was saved), or null.
	 */
	String message;
}
