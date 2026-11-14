package com.magnaboy.scripting;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a citizen's script as a state machine, advanced by {@link #tick()} once per client tick (20 ms) on the client
 * thread, after the citizen's movement step. It never blocks.
 * <p>
 * Every (re)start first walks to the host's base location, then runs the script's actions in order and wraps from
 * the last action to the first one, not to the base walk. Each action is followed by a wait of
 * {@code secondsTilNextAction}, at least 100 ms.
 * <p>
 * A tick that ends a wait starts the next action in the same tick, so an action that waits N ticks starts the next
 * one exactly N ticks after it started.
 */
@Slf4j
public final class ScriptRunner {
	static final int CLIENT_TICKS_PER_SECOND = 50;
	static final int MIN_WAIT_TICKS = 5;
	static final int PRE_WALK_TICKS_PER_TILE = 5;
	static final int FACE_DIRECTION_SETTLE_TICKS = 3;
	static final int ANIMATION_WATCHDOG_MARGIN_TICKS = 50;
	static final int FALLBACK_ANIMATION_LOOP_TICKS = 500;
	/**
	 * The action index of the walk to the base location that precedes the script's first action.
	 */
	static final int BASE_WALK = -1;

	enum State {
		/**
		 * Not started: the next tick starts the walk to the base location.
		 */
		START,
		/**
		 * Waiting before a walk, longer for a longer walk.
		 */
		PRE_WALK,
		WALKING,
		ANIMATING,
		/**
		 * Waiting after an action.
		 */
		WAIT,
	}

	private final ScriptHost host;
	private State state;
	private int actionIndex;
	private ScriptAction currentAction;
	/**
	 * Ticks left in {@link State#PRE_WALK} and {@link State#WAIT}; in {@link State#ANIMATING}, ticks until the watchdog
	 * gives up on the animation.
	 */
	private int ticksRemaining;
	/**
	 * How many actions have started, the base walk included. Lets tests see a new start even when the index does not
	 * change (a one-action script wrapping to itself).
	 */
	private int actionStarts;

	public ScriptRunner(ScriptHost host) {
		this.host = host;
		reset();
	}

	/**
	 * The number of client ticks to wait after an action.
	 */
	static int waitTicks(ScriptAction action) {
		if (action == null) {
			return MIN_WAIT_TICKS;
		}
		return Math.max(MIN_WAIT_TICKS, Math.round(action.secondsTilNextAction * CLIENT_TICKS_PER_SECOND));
	}

	private static ScriptAction baseWalk(ScriptHost host) {
		ScriptAction walk = new ScriptAction();
		walk.action = ActionType.WalkTo;
		walk.targetPosition = host.getBaseLocation();
		walk.secondsTilNextAction = 0f;
		return walk;
	}

	/**
	 * Back to the initial state: the next {@link #tick()} restarts the script with the walk to the base location.
	 */
	public void reset() {
		state = State.START;
		actionIndex = BASE_WALK;
		currentAction = null;
		ticksRemaining = 0;
	}

	/**
	 * The action in progress, including its wait; the synthetic base walk while that runs; null before the start.
	 */
	public ScriptAction getCurrentAction() {
		return currentAction;
	}

	State getState() {
		return state;
	}

	int getActionIndex() {
		return actionIndex;
	}

	int getActionStarts() {
		return actionStarts;
	}

	public void tick() {
		switch (state) {
			case START:
				if (hasActions()) {
					startAction(baseWalk(host));
				}
				break;
			case PRE_WALK:
				if (--ticksRemaining <= 0) {
					beginWalk();
				}
				break;
			case WALKING:
				if (!host.isWalking()) {
					startWait(waitTicks(currentAction));
				}
				break;
			case ANIMATING:
				if (host.isAnimationFinished()) {
					startWait(waitTicks(currentAction));
				} else if (--ticksRemaining <= 0) {
					ScriptFile script = host.getScript();
					log.debug("Animation {} in script {} at base {} did not finish in time; returning to idle",
						currentAction.animationId, script == null ? null : script.name, host.getBaseLocation());
					host.playIdleAnimation();
					startWait(waitTicks(currentAction));
				}
				break;
			case WAIT:
				if (--ticksRemaining <= 0) {
					startNextAction();
				}
				break;
		}
	}

	private boolean hasActions() {
		ScriptFile script = host.getScript();
		return script != null && script.actions != null && !script.actions.isEmpty();
	}

	private void startNextAction() {
		if (!hasActions()) {
			reset();
			return;
		}
		List<ScriptAction> actions = host.getScript().actions;
		actionIndex = (actionIndex + 1) % actions.size();
		startAction(actions.get(actionIndex));
	}

	/**
	 * Starts an action. One that is missing what it needs (a malformed script entry) only waits.
	 */
	private void startAction(ScriptAction action) {
		actionStarts++;
		currentAction = action;
		if (action == null || action.action == null) {
			startWait(waitTicks(action));
			return;
		}

		switch (action.action) {
			case Say:
				if (action.message != null) {
					host.say(action.message);
				}
				startWait(waitTicks(action));
				break;
			case WalkTo:
				if (action.targetPosition == null) {
					startWait(waitTicks(action));
					break;
				}
				state = State.PRE_WALK;
				ticksRemaining = (host.distanceTo(action.targetPosition) + 1) * PRE_WALK_TICKS_PER_TILE;
				break;
			case Animation:
				startAnimation(action);
				break;
			case FaceDirection:
				if (action.targetRotation == null) {
					startWait(waitTicks(action));
					break;
				}
				host.faceDirection(action.targetRotation.getAngle());
				startWait(FACE_DIRECTION_SETTLE_TICKS + waitTicks(action));
				break;
			case Idle:
			default:
				startWait(waitTicks(action));
				break;
		}
	}

	private void beginWalk() {
		Integer orientation = currentAction.targetRotation == null ? null : currentAction.targetRotation.getAngle();
		if (host.beginWalk(currentAction.targetPosition, orientation)) {
			state = State.WALKING;
		} else {
			startWait(waitTicks(currentAction));
		}
	}

	private void startAnimation(ScriptAction action) {
		if (action.animationId == null) {
			startWait(waitTicks(action));
			return;
		}
		int loops = action.timesToLoop == null ? 1 : Math.max(1, action.timesToLoop);
		int loopTicks = host.beginAnimation(action.animationId.getId(), loops);
		if (loopTicks == ScriptHost.ANIMATION_NOT_STARTED) {
			startWait(waitTicks(action));
			return;
		}
		if (loopTicks <= ScriptHost.ANIMATION_LENGTH_UNKNOWN) {
			loopTicks = FALLBACK_ANIMATION_LOOP_TICKS;
		}
		state = State.ANIMATING;
		ticksRemaining = loops * loopTicks + ANIMATION_WATCHDOG_MARGIN_TICKS;
	}

	private void startWait(int ticks) {
		state = State.WAIT;
		ticksRemaining = ticks;
	}
}
