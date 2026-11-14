package com.magnaboy;

import com.magnaboy.scripting.ScriptAction;
import com.magnaboy.scripting.ScriptHost;
import java.util.UUID;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scripted animation's loop counting, against the real {@link AnimationController} the client ticks.
 */
public class ScriptedCitizenTest {
	private static final int ANIMATION_ID = AnimationID.HerbloreMix.getId();
	private static final int[] FRAME_LENGTHS = {2, 2, 2};

	private Client client;
	private Animation animation;
	private Animation idle;
	private ScriptedCitizen citizen;
	private RuneLiteObject rlObject;

	@Before
	public void setUp() {
		client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);

		animation = mock(Animation.class);
		when(animation.isMayaAnim()).thenReturn(false);
		when(animation.getFrameLengths()).thenReturn(FRAME_LENGTHS);
		when(animation.getNumFrames()).thenReturn(FRAME_LENGTHS.length);
		when(animation.getDuration()).thenReturn(FRAME_LENGTHS.length);
		// loop() would restart from frame 3 - 1 = 2; each script loop must restart from frame 0
		when(animation.getFrameStep()).thenReturn(1);
		when(client.loadAnimation(ANIMATION_ID)).thenReturn(animation);

		idle = mock(Animation.class);
		when(client.loadAnimation(AnimationID.HumanIdle.getId())).thenReturn(idle);

		CitizensPlugin plugin = new CitizensPlugin();
		plugin.client = client;
		citizen = new ScriptedCitizen(plugin).setIdleAnimation(AnimationID.HumanIdle).setUUID(UUID.randomUUID());
		rlObject = mock(RuneLiteObject.class);
		citizen.rlObject = rlObject;
	}

	private AnimationController installedController() {
		ArgumentCaptor<AnimationController> captor = ArgumentCaptor.forClass(AnimationController.class);
		verify(rlObject).setAnimationController(captor.capture());
		return captor.getValue();
	}

	@Test
	public void scriptedAnimationLoopsFromTheFirstFrameThenReturnsToIdle() {
		int loopTicks = citizen.beginAnimation(ANIMATION_ID, 3);

		Assert.assertEquals(6, loopTicks);
		Assert.assertEquals(ANIMATION_ID, citizen.getAnimationID());
		AnimationController controller = installedController();
		Assert.assertSame(animation, controller.getAnimation());

		// The controller moves to the next frame once more ticks than the frame length have passed, so one play of
		// frames {2, 2, 2} reaches its end on the 7th tick
		int ticks = 0;
		int loopsSeen = 0;
		while (!citizen.isAnimationFinished() && ticks < 1000) {
			int frameBefore = controller.getFrame();
			controller.tick(1);
			ticks++;
			if (!citizen.isAnimationFinished() && controller.getFrame() < frameBefore) {
				loopsSeen++;
				Assert.assertEquals("restarted from frame 0", 0, controller.getFrame());
			}
		}

		Assert.assertTrue(citizen.isAnimationFinished());
		Assert.assertEquals(2, loopsSeen);
		Assert.assertEquals(3 * 7, ticks);
		verify(rlObject).setAnimation(idle);
		Assert.assertEquals((int) AnimationID.HumanIdle.getId(), citizen.getAnimationID());
		Assert.assertTrue(ticks <= 3 * loopTicks + 50);
	}

	@Test
	public void unfinishedAnimationIsNotReportedFinished() {
		citizen.beginAnimation(ANIMATION_ID, 1);
		AnimationController controller = installedController();

		for (int i = 0; i < 6; i++) {
			controller.tick(1);
		}

		Assert.assertFalse(citizen.isAnimationFinished());
		verify(rlObject, never()).setAnimation(idle);
	}

	@Test
	public void animationThatDoesNotLoadDoesNotStart() {
		when(client.loadAnimation(ANIMATION_ID)).thenReturn(null);

		Assert.assertEquals(ScriptHost.ANIMATION_NOT_STARTED, citizen.beginAnimation(ANIMATION_ID, 1));
		verify(rlObject, never()).setAnimationController(any());
		Assert.assertFalse(citizen.isAnimationFinished());
	}

	@Test
	public void lengthComesFromTheFrameLengthsOrTheMayaDuration() {
		Assert.assertEquals(6, ScriptedCitizen.lengthInClientTicks(animation));

		Animation maya = mock(Animation.class);
		when(maya.isMayaAnim()).thenReturn(true);
		when(maya.getDuration()).thenReturn(120);
		Assert.assertEquals(120, ScriptedCitizen.lengthInClientTicks(maya));

		Animation unknown = mock(Animation.class);
		when(unknown.getFrameLengths()).thenReturn(null);
		Assert.assertEquals(ScriptHost.ANIMATION_LENGTH_UNKNOWN, ScriptedCitizen.lengthInClientTicks(unknown));
	}

	@Test
	public void despawnRestartsTheScript() {
		citizen.beginAnimation(ANIMATION_ID, 2);
		citizen.currentAction = new ScriptAction();

		citizen.despawn();

		Assert.assertNull(citizen.currentAction);
		Assert.assertFalse(citizen.isAnimationFinished());
	}
}
