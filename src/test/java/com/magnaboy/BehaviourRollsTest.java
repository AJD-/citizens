package com.magnaboy;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class BehaviourRollsTest {
	/**
	 * Returns a fixed value from every {@link #nextDouble()} and counts the calls.
	 */
	private static final class FixedRandom extends Random {
		private final double value;
		private int rolls;

		FixedRandom(double value) {
			this.value = value;
		}

		@Override
		public double nextDouble() {
			rolls++;
			return value;
		}
	}

	@Test
	public void noWanderWithinTheCooldown() {
		FixedRandom alwaysWins = new FixedRandom(0.0);
		int lastWander = 100;
		for (int tick = lastWander; tick < lastWander + BehaviourRolls.WANDER_COOLDOWN_TICKS; tick++) {
			Assert.assertFalse("tick " + tick, BehaviourRolls.rollWander(tick, lastWander, alwaysWins));
		}
		// The cooldown does not use up a roll
		Assert.assertEquals(0, alwaysWins.rolls);

		Assert.assertTrue(BehaviourRolls.rollWander(lastWander + 10, lastWander, alwaysWins));
	}

	@Test
	public void wanderDependsOnTheRollOnceTheCooldownHasPassed() {
		int lastWander = 100;
		int tick = lastWander + 10;
		Assert.assertTrue(BehaviourRolls.rollWander(tick, lastWander, new FixedRandom(0.0699)));
		Assert.assertFalse(BehaviourRolls.rollWander(tick, lastWander, new FixedRandom(0.07)));
		Assert.assertFalse(BehaviourRolls.rollWander(tick, lastWander, new FixedRandom(0.5)));
	}

	@Test
	public void noCooldownBeforeTheFirstWander() {
		Assert.assertTrue(BehaviourRolls.rollWander(0, null, new FixedRandom(0.0)));
		Assert.assertFalse(BehaviourRolls.rollWander(0, null, new FixedRandom(0.07)));
	}

	@Test
	public void remarkRollsAtOnePercent() {
		Assert.assertTrue(BehaviourRolls.rollRemark(new FixedRandom(0.0)));
		Assert.assertTrue(BehaviourRolls.rollRemark(new FixedRandom(0.0099)));
		Assert.assertFalse(BehaviourRolls.rollRemark(new FixedRandom(0.01)));
		Assert.assertFalse(BehaviourRolls.rollRemark(new FixedRandom(0.9)));
	}

	@Test
	public void seededRatesMatchTheConfiguredChances() {
		Random seeded = new Random(42);
		int remarks = 0;
		int wanders = 0;
		int trials = 100_000;
		for (int i = 0; i < trials; i++) {
			if (BehaviourRolls.rollRemark(seeded)) {
				remarks++;
			}
			if (BehaviourRolls.rollWander(i, null, seeded)) {
				wanders++;
			}
		}
		Assert.assertEquals(0.01, (double) remarks / trials, 0.002);
		Assert.assertEquals(0.07, (double) wanders / trials, 0.005);
	}
}
