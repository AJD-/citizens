package com.magnaboy.scripting;

import com.magnaboy.AnimationID;
import com.magnaboy.CardinalDirection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.coords.WorldPoint;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ScriptRunnerTest {
	private static final Path SCRIPT_DIR = Paths.get("src", "main", "resources", "Scripts");
	private static final WorldPoint BASE = new WorldPoint(3200, 3200, 0);
	/**
	 * Ticks from the start of the restart walk to the start of the first action when the citizen already stands on
	 * its base: (0 + 1) * 5 before the walk, one tick to see that it is over, then the minimum wait of 5.
	 */
	private static final int BASE_WALK_TICKS = 5 + 1 + 5;

	private FakeHost host;
	private ScriptRunner runner;

	/**
	 * Records every call. Walks take {@code walkTicksPerTile} polls of {@link #isWalking()} per tile; animations
	 * finish after {@code animationPollsToFinish} polls of {@link #isAnimationFinished()}, or never if negative.
	 */
	private static final class FakeHost implements ScriptHost {
		final List<String> calls = new ArrayList<>();
		ScriptFile script;
		WorldPoint baseLocation = BASE;
		WorldPoint location = BASE;
		boolean queueWalks = true;
		int walkTicksPerTile;
		int animationResult = 10;
		int animationPollsToFinish = 1;

		private WorldPoint walkTarget;
		private int walkTicksLeft;
		private int animationPollsLeft;

		@Override
		public ScriptFile getScript() {
			return script;
		}

		@Override
		public WorldPoint getBaseLocation() {
			return baseLocation;
		}

		@Override
		public void say(String message) {
			calls.add("say " + message);
		}

		@Override
		public int distanceTo(WorldPoint point) {
			return point.distanceTo2D(location);
		}

		@Override
		public boolean beginWalk(WorldPoint target, Integer orientation) {
			calls.add("walk " + target.getX() + "," + target.getY() + " facing " + orientation);
			if (!queueWalks) {
				return false;
			}
			walkTarget = target;
			walkTicksLeft = distanceTo(target) * walkTicksPerTile;
			return true;
		}

		@Override
		public boolean isWalking() {
			if (walkTarget == null) {
				return false;
			}
			if (walkTicksLeft > 0) {
				walkTicksLeft--;
				return true;
			}
			location = walkTarget;
			walkTarget = null;
			return false;
		}

		@Override
		public int beginAnimation(int animationId, int loops) {
			calls.add("animate " + animationId + " x" + loops);
			animationPollsLeft = animationPollsToFinish;
			return animationResult;
		}

		@Override
		public boolean isAnimationFinished() {
			if (animationPollsLeft < 0) {
				return false;
			}
			return --animationPollsLeft <= 0;
		}

		@Override
		public void playIdleAnimation() {
			calls.add("idle");
		}

		@Override
		public void faceDirection(int orientation) {
			calls.add("face " + orientation);
		}
	}

	@Before
	public void setUp() {
		host = new FakeHost();
		runner = new ScriptRunner(host);
	}

	private static ScriptFile script(ScriptAction... actions) {
		ScriptFile script = new ScriptFile();
		script.name = "test";
		script.actions = new ArrayList<>(Arrays.asList(actions));
		return script;
	}

	private static ScriptAction idle(float seconds) {
		ScriptAction action = new ScriptAction();
		action.action = ActionType.Idle;
		action.secondsTilNextAction = seconds;
		return action;
	}

	private static ScriptAction say(String message) {
		ScriptAction action = new ScriptAction();
		action.action = ActionType.Say;
		action.message = message;
		return action;
	}

	private static ScriptAction walkTo(int x, int y, float seconds) {
		ScriptAction action = new ScriptAction();
		action.action = ActionType.WalkTo;
		action.targetPosition = new WorldPoint(x, y, 0);
		action.secondsTilNextAction = seconds;
		return action;
	}

	private static ScriptAction face(CardinalDirection direction, float seconds) {
		ScriptAction action = new ScriptAction();
		action.action = ActionType.FaceDirection;
		action.targetRotation = direction;
		action.secondsTilNextAction = seconds;
		return action;
	}

	private static ScriptAction animation(AnimationID id, Integer timesToLoop) {
		ScriptAction action = new ScriptAction();
		action.action = ActionType.Animation;
		action.animationId = id;
		action.timesToLoop = timesToLoop;
		return action;
	}

	private void tick(int times) {
		for (int i = 0; i < times; i++) {
			runner.tick();
		}
	}

	/**
	 * Ticks until the runner moves to another action index, and returns how many ticks that took.
	 */
	private int ticksUntilNextAction() {
		int index = runner.getActionIndex();
		for (int ticks = 1; ticks <= 100_000; ticks++) {
			runner.tick();
			if (runner.getActionIndex() != index) {
				return ticks;
			}
		}
		throw new AssertionError("Stuck in action " + index + " in state " + runner.getState());
	}

	/**
	 * Runs the restart walk (the citizen is already on its base) up to the tick that starts action 0, and drops the
	 * walk from the recorded calls.
	 */
	private void finishBaseWalk() {
		runner.tick();
		Assert.assertEquals(ScriptRunner.BASE_WALK, runner.getActionIndex());
		Assert.assertEquals(BASE_WALK_TICKS, ticksUntilNextAction());
		Assert.assertEquals(0, runner.getActionIndex());
		Assert.assertEquals("walk 3200,3200 facing null", host.calls.remove(0));
	}

	@Test
	public void baseWalkRunsOnceThenTheScriptWrapsToItsFirstAction() {
		ScriptAction first = idle(0);
		ScriptAction second = say("hello");
		host.script = script(first, second);

		runner.tick();
		Assert.assertEquals(ScriptRunner.BASE_WALK, runner.getActionIndex());
		Assert.assertEquals(ActionType.WalkTo, runner.getCurrentAction().action);
		Assert.assertEquals(BASE, runner.getCurrentAction().targetPosition);

		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ticksUntilNextAction();
			indices.add(runner.getActionIndex());
		}

		Assert.assertEquals(Arrays.asList(0, 1, 0, 1, 0, 1), indices);
		Assert.assertEquals(1, host.calls.stream().filter(c -> c.startsWith("walk")).count());
		Assert.assertEquals(3, host.calls.stream().filter(c -> c.equals("say hello")).count());
	}

	@Test
	public void baseWalkTakesThePreWalkDelayTheWalkAndTheMinimumWait() {
		host.location = new WorldPoint(BASE.getX() + 3, BASE.getY(), 0);
		host.walkTicksPerTile = 2;
		host.script = script(idle(0), idle(0));

		runner.tick();
		Assert.assertEquals(ScriptRunner.State.PRE_WALK, runner.getState());
		// (3 + 1) * 5 ticks before the walk is queued
		tick(19);
		Assert.assertTrue(host.calls.isEmpty());
		tick(1);
		Assert.assertEquals(Arrays.asList("walk 3200,3200 facing null"), host.calls);
		Assert.assertEquals(ScriptRunner.State.WALKING, runner.getState());

		// Walking for 3 tiles * 2 ticks, then one tick to see that it ended
		tick(6);
		Assert.assertEquals(ScriptRunner.State.WALKING, runner.getState());
		tick(1);
		Assert.assertEquals(ScriptRunner.State.WAIT, runner.getState());
		Assert.assertEquals(ScriptRunner.BASE_WALK, runner.getActionIndex());
		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertEquals(0, runner.getActionIndex());
	}

	@Test
	public void idleWaitsItsSecondsWithAFloorOfFiveTicks() {
		host.script = script(idle(0), idle(3), idle(0.25f), idle(0.01f));
		finishBaseWalk();

		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertEquals(150, ticksUntilNextAction());
		Assert.assertEquals(13, ticksUntilNextAction());
		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertTrue(host.calls.isEmpty());
	}

	@Test
	public void faceDirectionTurnsAtOnceThenSettlesAndWaits() {
		host.script = script(face(CardinalDirection.East, 0), face(CardinalDirection.North, 0.25f), idle(0));
		finishBaseWalk();
		Assert.assertEquals(Arrays.asList("face 1536"), host.calls);
		host.calls.clear();

		Assert.assertEquals(3 + 5, ticksUntilNextAction());
		Assert.assertEquals(Arrays.asList("face 1024"), host.calls);
		Assert.assertEquals(3 + 13, ticksUntilNextAction());
	}

	@Test
	public void walkToWaitsInProportionToTheDistanceThenWalksThenWaits() {
		host.walkTicksPerTile = 1;
		ScriptAction walk = walkTo(BASE.getX(), BASE.getY() + 4, 0.25f);
		walk.targetRotation = CardinalDirection.West;
		host.script = script(walk, idle(0));
		finishBaseWalk();

		Assert.assertEquals(ScriptRunner.State.PRE_WALK, runner.getState());
		// (4 + 1) * 5 ticks after the action started, the walk is queued
		tick(24);
		Assert.assertTrue(host.calls.isEmpty());
		tick(1);
		Assert.assertEquals(Arrays.asList("walk 3200,3204 facing 512"), host.calls);

		// In progress for 4 ticks, seen to be over on the 5th, then 13 ticks of wait
		for (int i = 0; i < 4; i++) {
			runner.tick();
			Assert.assertEquals(ScriptRunner.State.WALKING, runner.getState());
		}
		runner.tick();
		Assert.assertEquals(ScriptRunner.State.WAIT, runner.getState());
		Assert.assertEquals(13, ticksUntilNextAction());
		Assert.assertEquals(1, runner.getActionIndex());
	}

	@Test
	public void walkThatQueuesNothingGoesStraightToTheWait() {
		host.queueWalks = false;
		host.script = script(walkTo(BASE.getX() + 1, BASE.getY(), 0.25f), idle(0));

		// The base walk itself queues nothing either: delay, attempt, wait
		runner.tick();
		Assert.assertEquals(5 + 5, ticksUntilNextAction());
		Assert.assertEquals(0, runner.getActionIndex());

		Assert.assertEquals(2 * 5 + 13, ticksUntilNextAction());
		Assert.assertEquals(1, runner.getActionIndex());
		Assert.assertEquals(2, host.calls.size());
	}

	@Test
	public void animationPlaysTheRequestedLoopsAndWaitsForTheHost() {
		host.animationResult = 10;
		host.animationPollsToFinish = 7;
		host.script = script(animation(AnimationID.HerbloreMix, 5), animation(AnimationID.Flex, null), idle(0));
		finishBaseWalk();
		Assert.assertEquals(Arrays.asList("animate " + AnimationID.HerbloreMix.getId() + " x5"), host.calls);

		// The host reports the end on the 7th tick
		tick(6);
		Assert.assertEquals(ScriptRunner.State.ANIMATING, runner.getState());
		runner.tick();
		Assert.assertEquals(ScriptRunner.State.WAIT, runner.getState());
		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertEquals(1, runner.getActionIndex());
		Assert.assertEquals("animate " + AnimationID.Flex.getId() + " x1", host.calls.get(1));
		// The host returned to idle by itself
		Assert.assertFalse(host.calls.contains("idle"));
	}

	@Test
	public void watchdogEndsAnAnimationThatNeverFinishes() {
		host.animationResult = 10;
		host.animationPollsToFinish = -1;
		host.script = script(animation(AnimationID.Flex, 3), idle(0));
		finishBaseWalk();

		tick(3 * 10 + 50 - 1);
		Assert.assertEquals(ScriptRunner.State.ANIMATING, runner.getState());
		Assert.assertFalse(host.calls.contains("idle"));
		runner.tick();
		Assert.assertEquals(ScriptRunner.State.WAIT, runner.getState());
		Assert.assertEquals(Arrays.asList("animate " + AnimationID.Flex.getId() + " x3", "idle"), host.calls);
	}

	@Test
	public void watchdogUsesAFixedCapWhenTheLengthIsUnknown() {
		host.animationResult = ScriptHost.ANIMATION_LENGTH_UNKNOWN;
		host.animationPollsToFinish = -1;
		host.script = script(animation(AnimationID.Flex, 2), idle(0));
		finishBaseWalk();

		tick(2 * 500 + 50 - 1);
		Assert.assertEquals(ScriptRunner.State.ANIMATING, runner.getState());
		runner.tick();
		Assert.assertEquals(ScriptRunner.State.WAIT, runner.getState());
		Assert.assertTrue(host.calls.contains("idle"));
	}

	@Test
	public void animationThatDoesNotStartGoesStraightToTheWait() {
		host.animationResult = ScriptHost.ANIMATION_NOT_STARTED;
		host.script = script(animation(AnimationID.Flex, 1), idle(0));
		finishBaseWalk();

		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertEquals(Arrays.asList("animate " + AnimationID.Flex.getId() + " x1"), host.calls);
	}

	@Test
	public void sayCallsTheHostOnceThenWaits() {
		host.script = script(say("Fight back, join H.A.M.!"), idle(0));
		finishBaseWalk();
		Assert.assertEquals(Arrays.asList("say Fight back, join H.A.M.!"), host.calls);

		Assert.assertEquals(5, ticksUntilNextAction());
		Assert.assertEquals(1, host.calls.size());
	}

	@Test
	public void resetMidActionRestartsWithTheBaseWalk() {
		host.script = script(idle(0), idle(3));
		finishBaseWalk();
		ticksUntilNextAction();
		tick(40);
		Assert.assertEquals(1, runner.getActionIndex());

		runner.reset();

		Assert.assertEquals(ScriptRunner.State.START, runner.getState());
		Assert.assertEquals(ScriptRunner.BASE_WALK, runner.getActionIndex());
		Assert.assertNull(runner.getCurrentAction());

		runner.tick();
		Assert.assertEquals(ScriptRunner.State.PRE_WALK, runner.getState());
		Assert.assertEquals(ActionType.WalkTo, runner.getCurrentAction().action);
		Assert.assertEquals(BASE, runner.getCurrentAction().targetPosition);
		Assert.assertEquals(BASE_WALK_TICKS, ticksUntilNextAction());
		Assert.assertEquals(0, runner.getActionIndex());
	}

	@Test
	public void missingOrEmptyScriptDoesNothing() {
		ScriptFile noActions = new ScriptFile();
		for (ScriptFile script : Arrays.asList(null, script(), noActions)) {
			host.script = script;
			runner.reset();
			tick(1000);
			Assert.assertEquals(ScriptRunner.State.START, runner.getState());
			Assert.assertNull(runner.getCurrentAction());
		}
		Assert.assertTrue(host.calls.isEmpty());
	}

	@Test
	public void malformedActionsOnlyWait() {
		ScriptAction noType = new ScriptAction();
		ScriptAction walkNowhere = new ScriptAction();
		walkNowhere.action = ActionType.WalkTo;
		ScriptAction faceNowhere = new ScriptAction();
		faceNowhere.action = ActionType.FaceDirection;
		ScriptAction noAnimation = new ScriptAction();
		noAnimation.action = ActionType.Animation;
		host.script = script(null, noType, walkNowhere, faceNowhere, noAnimation, idle(0));
		finishBaseWalk();

		for (int i = 1; i <= 5; i++) {
			Assert.assertEquals(5, ticksUntilNextAction());
			Assert.assertEquals(i, runner.getActionIndex());
		}
		Assert.assertTrue(host.calls.isEmpty());
	}

	private static List<String> bundledScriptNames() throws IOException {
		try (Stream<Path> stream = Files.list(SCRIPT_DIR)) {
			return stream.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.map(name -> name.substring(0, name.length() - ".json".length()))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	@Test
	public void everyBundledScriptVisitsEachActionAndWraps() throws IOException {
		List<String> names = bundledScriptNames();
		Assert.assertFalse("No scripts found in " + SCRIPT_DIR.toAbsolutePath(), names.isEmpty());
		ScriptLoader loader = new ScriptLoader(RuneLiteAPI.GSON);

		List<String> failures = new ArrayList<>();
		for (String name : names) {
			ScriptFile script = loader.loadScript(name);
			Assert.assertNotNull(name, script);
			int actionCount = script.actions.size();

			host = new FakeHost();
			host.script = script;
			// Roughly real speeds: a tile takes 32 client ticks, a loop of an animation 100
			host.walkTicksPerTile = 32;
			host.animationResult = 100;
			host.animationPollsToFinish = 100;
			// Start a few tiles from the first walk target, which stands in for the base
			WorldPoint firstTarget = script.actions.stream()
				.filter(a -> a.targetPosition != null)
				.map(a -> a.targetPosition)
				.findFirst()
				.orElse(BASE);
			host.baseLocation = firstTarget;
			host.location = new WorldPoint(firstTarget.getX() + 5, firstTarget.getY(), firstTarget.getPlane());
			runner = new ScriptRunner(host);

			TreeSet<Integer> visited = new TreeSet<>();
			boolean wrapped = false;
			int previous = ScriptRunner.BASE_WALK;
			int ticks = 0;
			while (!wrapped && ticks < 200_000) {
				runner.tick();
				ticks++;
				int index = runner.getActionIndex();
				if (index != previous) {
					visited.add(index);
					wrapped = previous == actionCount - 1 && index == 0;
					previous = index;
				}
			}

			if (!wrapped || visited.size() != actionCount) {
				failures.add(name + ": visited " + visited + " of " + actionCount + " actions, wrapped " + wrapped
					+ ", stopped in " + runner.getState() + " at index " + runner.getActionIndex() + " after "
					+ ticks + " ticks");
			}
		}

		if (!failures.isEmpty()) {
			Assert.fail(String.join("\n", failures));
		}
	}
}
