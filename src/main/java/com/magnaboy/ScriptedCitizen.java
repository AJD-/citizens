package com.magnaboy;

import com.magnaboy.scripting.ScriptAction;
import com.magnaboy.scripting.ScriptFile;
import com.magnaboy.scripting.ScriptHost;
import com.magnaboy.scripting.ScriptRunner;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.coords.WorldPoint;

/**
 * A citizen that runs a script. The script restarts, with a walk back to {@link #baseLocation}, every time the citizen
 * spawns, and advances once per client tick while the citizen is spawned.
 */
@Slf4j
public class ScriptedCitizen extends Citizen<ScriptedCitizen> implements ScriptHost {
	/**
	 * The action in progress, for the debug overlay.
	 */
	public ScriptAction currentAction;
	public WorldPoint baseLocation;
	private ScriptFile script;
	private final ScriptRunner runner = new ScriptRunner(this);
	private int animationLoopsLeft;
	private boolean animationFinished;

	public ScriptedCitizen(CitizensPlugin plugin) {
		super(plugin);
		entityType = EntityType.ScriptedCitizen;
	}

	/**
	 * The length of one play of the animation in client ticks, or {@link #ANIMATION_LENGTH_UNKNOWN}.
	 */
	static int lengthInClientTicks(Animation animation) {
		if (animation.isMayaAnim()) {
			return Math.max(ANIMATION_LENGTH_UNKNOWN, animation.getDuration());
		}
		int[] frameLengths = animation.getFrameLengths();
		if (frameLengths == null) {
			return ANIMATION_LENGTH_UNKNOWN;
		}
		int total = 0;
		for (int frameLength : frameLengths) {
			total += frameLength;
		}
		return Math.max(ANIMATION_LENGTH_UNKNOWN, total);
	}

	public ScriptedCitizen setScript(ScriptFile script) {
		this.script = script;
		return this;
	}

	@Override
	public ScriptFile getScript() {
		return script;
	}

	@Override
	public WorldPoint getBaseLocation() {
		return baseLocation;
	}

	@Override
	public boolean spawn() {
		boolean didSpawn = super.spawn();
		if (didSpawn) {
			resetScript();
		}
		return didSpawn;
	}

	@Override
	public boolean despawn() {
		resetScript();
		return super.despawn();
	}

	private void resetScript() {
		runner.reset();
		currentAction = null;
		animationLoopsLeft = 0;
		animationFinished = false;
	}

	/**
	 * Client thread only. Moves the citizen, then advances the script.
	 */
	@Override
	public void onClientTick() {
		super.onClientTick();
		// The movement step can despawn the citizen
		if (isActive()) {
			runner.tick();
			currentAction = runner.getCurrentAction();
		}
	}

	@Override
	public int distanceTo(WorldPoint point) {
		return point.distanceTo2D(getWorldLocation());
	}

	@Override
	public boolean beginWalk(WorldPoint target, Integer orientation) {
		moveTo(target, orientation, false, false);
		if (getCurrentTarget() == null) {
			log.debug("{} cannot walk to {}: it is not in the scene on the citizen's plane", debugName(), target);
			return false;
		}
		return true;
	}

	@Override
	public boolean isWalking() {
		return getCurrentTarget() != null;
	}

	@Override
	public int beginAnimation(int animationId, int loops) {
		animationLoopsLeft = loops;
		animationFinished = false;
		Animation animation = setAnimation(animationId, this::onScriptAnimationFinished);
		if (animation == null) {
			log.debug("{} cannot play animation {}: it did not load", debugName(), animationId);
			return ANIMATION_NOT_STARTED;
		}
		return lengthInClientTicks(animation);
	}

	/**
	 * Called by the client when the scripted animation reaches its end: plays it again from the first frame while
	 * loops remain, otherwise returns to the idle animation.
	 */
	private void onScriptAnimationFinished(AnimationController controller) {
		animationLoopsLeft--;
		if (animationLoopsLeft > 0) {
			controller.reset();
			return;
		}
		animationFinished = true;
		playIdleAnimation();
	}

	@Override
	public boolean isAnimationFinished() {
		return animationFinished;
	}

	@Override
	public void playIdleAnimation() {
		if (idleAnimationId != null) {
			setAnimation(idleAnimationId.getId());
		}
	}

	@Override
	public void faceDirection(int orientation) {
		setOrientation(orientation);
	}
}
