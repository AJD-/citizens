package com.magnaboy;

import org.junit.Assert;
import org.junit.Test;

public class EditorOrientationTest {
	/**
	 * Stored value, through the dropdown untouched, back to a stored value.
	 */
	private static int roundTrip(Integer stored) {
		CardinalDirection shown = EditorOrientation.toDropdown(stored);
		Integer custom = EditorOrientation.customValue(stored);
		return EditorOrientation.fromDropdown(shown, custom);
	}

	@Test
	public void nonCardinalValueIsKept() {
		// Damien in 12342.json
		Assert.assertNull(EditorOrientation.toDropdown(500));
		Assert.assertEquals(Integer.valueOf(500), EditorOrientation.customValue(500));
		Assert.assertEquals(500, roundTrip(500));
	}

	@Test
	public void everyValueInRangeIsKept() {
		for (int value = 0; value < Util.JAU_FULL_ROTATION; value++) {
			Assert.assertEquals(value, roundTrip(value));
		}
	}

	@Test
	public void cardinalValueSelectsItsDirection() {
		for (CardinalDirection direction : CardinalDirection.values()) {
			Assert.assertEquals(direction, EditorOrientation.toDropdown(direction.getAngle()));
			Assert.assertNull(EditorOrientation.customValue(direction.getAngle()));
		}
	}

	@Test
	public void missingValueShowsSouth() {
		Assert.assertEquals(CardinalDirection.South, EditorOrientation.toDropdown(null));
		Assert.assertNull(EditorOrientation.customValue(null));
		Assert.assertEquals(CardinalDirection.South.getAngle(), roundTrip(null));
	}

	@Test
	public void pickedDirectionReplacesTheCustomValue() {
		Assert.assertEquals(CardinalDirection.West.getAngle(), EditorOrientation.fromDropdown(CardinalDirection.West, 500));
	}
}
