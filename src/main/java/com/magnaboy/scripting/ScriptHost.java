package com.magnaboy.scripting;

import net.runelite.api.coords.WorldPoint;

/**
 * What a {@link ScriptRunner} needs from the citizen it drives. Every method is called on the client thread.
 */
public interface ScriptHost {
	/**
	 * Returned by {@link #beginAnimation(int, int)} when the animation could not be started.
	 */
	int ANIMATION_NOT_STARTED = -1;
	/**
	 * Returned by {@link #beginAnimation(int, int)} when the animation started but its length is not known.
	 */
	int ANIMATION_LENGTH_UNKNOWN = 0;

	/**
	 * The script to run, or null for none.
	 */
	ScriptFile getScript();

	/**
	 * Where the citizen walks before the script's first action, each time the script (re)starts.
	 */
	WorldPoint getBaseLocation();

	void say(String message);

	/**
	 * Tile distance, ignoring the plane, from the citizen's location to {@code point}.
	 */
	int distanceTo(WorldPoint point);

	/**
	 * Starts walking to {@code target}.
	 *
	 * @param orientation the orientation to face on arrival, or null
	 * @return whether a walk was queued
	 */
	boolean beginWalk(WorldPoint target, Integer orientation);

	boolean isWalking();

	/**
	 * Plays an animation {@code loops} times, each from its first frame, then returns to the idle animation.
	 *
	 * @return the length of one loop in client ticks, {@link #ANIMATION_LENGTH_UNKNOWN}, or
	 * {@link #ANIMATION_NOT_STARTED}
	 */
	int beginAnimation(int animationId, int loops);

	/**
	 * Whether the animation from the last {@link #beginAnimation(int, int)} has played all its loops.
	 */
	boolean isAnimationFinished();

	void playIdleAnimation();

	/**
	 * Turns to the orientation at once, without a turning animation.
	 */
	void faceDirection(int orientation);
}
