package com.magnaboy;

import java.util.Random;

/**
 * The per-game-tick chances behind remarks and wandering. Pure: no client access, and the caller supplies the
 * randomness.
 */
final class BehaviourRolls {
	static final double REMARK_CHANCE_PER_TICK = 0.01;
	static final double WANDER_CHANCE_PER_TICK = 0.07;
	static final int WANDER_COOLDOWN_TICKS = 10;

	private BehaviourRolls() {
	}

	/**
	 * Whether a citizen that could make a remark makes one this game tick.
	 */
	static boolean rollRemark(Random random) {
		return random.nextDouble() < REMARK_CHANCE_PER_TICK;
	}

	/**
	 * Whether a wandering citizen with no movement target starts a wander this game tick. Rolls only once the cooldown
	 * since the last wander has passed.
	 *
	 * @param gameTick       the current game tick count
	 * @param lastWanderTick the game tick count at which the last wander began, or null if it has never wandered
	 */
	static boolean rollWander(int gameTick, Integer lastWanderTick, Random random) {
		if (lastWanderTick != null && gameTick - lastWanderTick < WANDER_COOLDOWN_TICKS) {
			return false;
		}
		return random.nextDouble() < WANDER_CHANCE_PER_TICK;
	}
}
