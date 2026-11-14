package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AnimMatcherTest {
	private final FakeCache oldCache = new FakeCache();
	private final FakeCache newCache = new FakeCache();

	@Test
	public void sameFramesAndSkeletonIsUnchanged() {
		oldCache.sequence(808, 0, 21, 21);
		newCache.sequence(808, 0, 21, 21);
		AnimMatcher.Verdict v = new AnimMatcher(oldCache, newCache).compare(808);
		assertFalse(v.changed);
		assertNull(v.proposal);
	}

	@Test
	public void changedSequenceIsProposedFromStandAndWalkVotes() {
		oldCache.sequence(195, 319, 21, 21);
		newCache.sequence(195, 0, 4, 4, 8);
		for (int i = 0; i < 5; i++) {
			oldCache.animatedNpc(i, 195, 189);
			newCache.animatedNpc(i, 2331, 12042);
		}
		oldCache.animatedNpc(9, 808, 195);
		newCache.animatedNpc(9, 808, 2331);
		AnimMatcher.Verdict v = new AnimMatcher(oldCache, newCache).compare(195);
		assertTrue(v.changed);
		assertTrue(v.differences.contains("skeleton 319 -> 0"));
		assertEquals(Integer.valueOf(2331), v.proposal);
		assertEquals(Integer.valueOf(6), v.votes.get(2331));
	}

	@Test
	public void npcsThatLostTheirAnimationCastNoVote() {
		oldCache.sequence(195, 319, 21, 21);
		newCache.sequence(195, 0, 4, 4, 8);
		for (int i = 0; i < 5; i++) {
			oldCache.animatedNpc(i, 195, -1);
			newCache.animatedNpc(i, -1, -1);
		}
		AnimMatcher.Verdict v = new AnimMatcher(oldCache, newCache).compare(195);
		assertTrue(v.changed);
		assertNull(v.proposal);
		assertFalse(v.votes.containsKey(-1));
	}

	@Test
	public void anIdInNeitherCacheIsAbsentNotUnchanged() {
		AnimMatcher.Verdict v = new AnimMatcher(oldCache, newCache).compare(4242);
		assertEquals(AnimMatcher.ABSENT, v.status);
		assertFalse(v.changed);
		assertEquals("absent from both caches", v.differences.get(0));
	}

	@Test
	public void changedSequenceStillUsedByNpcsHasNoProposal() {
		oldCache.sequence(6484, 319, 15, 15);
		newCache.sequence(6484, 2402, 15, 15);
		oldCache.animatedNpc(1, 6484, -1);
		newCache.animatedNpc(1, 6484, -1);
		AnimMatcher.Verdict v = new AnimMatcher(oldCache, newCache).compare(6484);
		assertTrue(v.changed);
		assertNull(v.proposal);
	}
}
