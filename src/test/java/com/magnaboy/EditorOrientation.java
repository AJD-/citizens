package com.magnaboy;

/**
 * How the editor's orientation dropdown shows a stored orientation and turns the dropdown back into one. The dropdown
 * only lists the {@link CardinalDirection}s, but data may hold any value from 0 to 2047. Such a value leaves the
 * dropdown unselected (the panel shows it as "Custom (value)") and is kept until the contributor picks a direction.
 */
final class EditorOrientation {
	private EditorOrientation() {
	}

	/**
	 * The dropdown item for a stored orientation: its direction, South when there is none, or null when no direction
	 * has that angle.
	 */
	static CardinalDirection toDropdown(Integer stored) {
		if (stored == null) {
			return CardinalDirection.South;
		}
		for (CardinalDirection direction : CardinalDirection.values()) {
			if (direction.getAngle() == stored) {
				return direction;
			}
		}
		return null;
	}

	/**
	 * The stored orientation to keep while the dropdown is unselected, or null when the dropdown can show it.
	 */
	static Integer customValue(Integer stored) {
		return toDropdown(stored) == null ? stored : null;
	}

	/**
	 * The orientation to store: the picked direction's angle, otherwise the custom value, otherwise South's.
	 */
	static int fromDropdown(CardinalDirection selected, Integer custom) {
		if (selected != null) {
			return selected.getAngle();
		}
		if (custom != null) {
			return custom;
		}
		return CardinalDirection.South.getAngle();
	}
}
