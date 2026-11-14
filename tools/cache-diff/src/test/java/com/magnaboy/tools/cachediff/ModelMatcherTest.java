package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.magnaboy.tools.cachediff.ModelMatcher.Rule;
import com.magnaboy.tools.cachediff.ModelMatcher.Status;
import com.magnaboy.tools.cachediff.ModelMatcher.Verdict;
import org.junit.Test;

public class ModelMatcherTest {
	private static final int OLD = 10;
	private static final int NEW = 20;
	private static final int OTHER = 30;

	/** Old model 10 with geometry "a"; in the new cache 10 has been remade as "b" and 20 exists. */
	private final FakeCache oldCache = new FakeCache().model(OLD, "a").model(1, "body");
	private final FakeCache newCache = new FakeCache().model(OLD, "b").model(NEW, "c").model(OTHER, "d").model(1, "body");

	private Verdict verdict() {
		return new ModelMatcher(oldCache, newCache).citizenModel(OLD);
	}

	/** {@code count} NPCs (ids from {@code firstId}) whose list [OLD, 1] became [to, 1]. */
	private void votes(int firstId, int count, int to) {
		for (int i = firstId; i < firstId + count; i++) {
			oldCache.npc(i, OLD, 1);
			newCache.npc(i, to, 1);
		}
	}

	private static void assertNoProposal(Verdict v, Status status) {
		assertEquals(status, v.status);
		assertNull(v.proposal);
		assertNull(v.rule);
	}

	private static void assertProposal(Verdict v, Status status, int to, Rule rule) {
		assertEquals(status, v.status);
		assertEquals(Integer.valueOf(to), v.proposal);
		assertEquals(rule, v.rule);
	}

	@Test
	public void identicalHashIsNeverRemappedEvenWithKitAndVotesAgainstIt() {
		newCache.model(OLD, "a");
		oldCache.kit(1, OLD);
		newCache.kit(1, NEW);
		votes(100, 10, NEW);
		assertNoProposal(verdict(), Status.IDENTICAL);
	}

	@Test
	public void kitAndVotesAgreeingIsAProposal() {
		oldCache.kit(7, OLD);
		newCache.kit(7, NEW);
		votes(100, 4, NEW);
		Verdict v = verdict();
		assertProposal(v, Status.REPLACED, NEW, Rule.KIT_AND_VOTES);
		assertEquals(Integer.valueOf(4), v.votes.get(NEW));
		assertTrue(v.kitTargets.get(NEW).contains(7));
	}

	@Test
	public void votesWithoutKitAreUnresolvedWhileTheOldIdIsStillUsed() {
		votes(100, 10, NEW);
		newCache.npc(500, OLD);
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void splitVotesWithoutKitAreUnresolved() {
		votes(100, 3, NEW);
		votes(200, 3, OTHER);
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void nearUnanimousVotesForAnIdNoLongerUsedAreAVotesOnlyProposal() {
		votes(100, 20, NEW);
		votes(200, 1, OTHER);
		Verdict v = verdict();
		assertProposal(v, Status.REPLACED, NEW, Rule.VOTES_ONLY);
		assertEquals(21, v.totalVotes());
	}

	@Test
	public void votesOnlyAtExactlyTheMinimumShareIsAccepted() {
		votes(100, 9, NEW);
		votes(200, 1, OTHER);
		assertProposal(verdict(), Status.REPLACED, NEW, Rule.VOTES_ONLY);
	}

	@Test
	public void votesOnlyBelowTheMinimumShareIsUnresolved() {
		votes(100, 9, NEW);
		votes(200, 2, OTHER);
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void votesOnlyWithTooFewVotesIsUnresolved() {
		votes(100, ModelMatcher.VOTES_ONLY_MIN_VOTES - 1, NEW);
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void votesOnlyWithMinimumVotesIsAccepted() {
		votes(100, ModelMatcher.VOTES_ONLY_MIN_VOTES, NEW);
		assertProposal(verdict(), Status.REPLACED, NEW, Rule.VOTES_ONLY);
	}

	@Test
	public void exactGeometryMoveIsAProposal() {
		newCache.model(OTHER, "a");
		Verdict v = verdict();
		assertProposal(v, Status.MOVED, OTHER, Rule.GEOMETRY_MOVE);
	}

	@Test
	public void geometryMoveWorksWhenTheOldIdIsGone() {
		FakeCache gone = new FakeCache().model(OTHER, "a");
		Verdict v = new ModelMatcher(oldCache, gone).citizenModel(OLD);
		assertProposal(v, Status.MOVED, OTHER, Rule.GEOMETRY_MOVE);
	}

	@Test
	public void aDuplicateThatAlreadyExistedInTheOldCacheIsNotAMove() {
		oldCache.model(OTHER, "a");
		newCache.model(OTHER, "a");
		votes(100, 6, OLD);
		assertNoProposal(verdict(), Status.CHANGED_IN_PLACE);
	}

	@Test
	public void ambiguousGeometryIsNotAMove() {
		newCache.model(OTHER, "a").model(31, "a");
		Verdict v = verdict();
		assertNoProposal(v, Status.UNRESOLVED);
		assertEquals(2, v.geometryMatches.size());
	}

	@Test
	public void inPlaceRemakeIsChangedInPlaceWithoutProposal() {
		votes(100, 5, OLD);
		assertNoProposal(verdict(), Status.CHANGED_IN_PLACE);
	}

	@Test
	public void inPlaceNeedsTheIdKeptInMostNpcsThatUsedIt() {
		votes(100, 1, OLD);
		for (int i = 200; i < 203; i++) {
			oldCache.npc(i, OLD, 1);
			newCache.npc(i, OLD, 1, 2);
		}
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void kitMappingToItselfIsChangedInPlace() {
		oldCache.kit(3, OLD);
		newCache.kit(3, OLD);
		assertNoProposal(verdict(), Status.CHANGED_IN_PLACE);
	}

	@Test
	public void kitMappingWithoutAnyNpcVotesIsAKitOnlyProposal() {
		oldCache.kit(3, OLD);
		newCache.kit(3, NEW);
		oldCache.npc(100, OLD, 1);
		newCache.npc(100, NEW, 1, 2);
		assertProposal(verdict(), Status.REPLACED, NEW, Rule.KIT_ONLY);
	}

	@Test
	public void kitAndVotesDisagreeingIsUnresolved() {
		oldCache.kit(3, OLD);
		newCache.kit(3, NEW);
		votes(100, 5, OTHER);
		assertNoProposal(verdict(), Status.UNRESOLVED);
	}

	@Test
	public void kitTargetTiedWithAnotherTargetInVotesIsUnresolved() {
		oldCache.kit(3, OLD);
		newCache.kit(3, NEW);
		votes(100, 3, NEW);
		votes(200, 3, OTHER);
		Verdict v = verdict();
		assertNoProposal(v, Status.UNRESOLVED);
		assertEquals("top votes tied", v.note);
	}

	@Test
	public void absentOrUndecodableOldModelIsNotInOld() {
		Verdict absent = new ModelMatcher(oldCache, newCache).citizenModel(99);
		assertNoProposal(absent, Status.NOT_IN_OLD);
		assertTrue(absent.note.contains("absent"));

		oldCache.undecodable(OLD);
		votes(100, 5, NEW);
		Verdict undecodable = verdict();
		assertNoProposal(undecodable, Status.NOT_IN_OLD);
		assertTrue(undecodable.note.contains("undecodable"));
	}

	@Test
	public void goneWithoutEvidenceIsMissingInNew() {
		FakeCache gone = new FakeCache();
		assertNoProposal(new ModelMatcher(oldCache, gone).citizenModel(OLD), Status.MISSING_IN_NEW);
	}

	@Test
	public void objectModelsAreIdenticalOrChangedAndNeverProposed() {
		oldCache.kit(3, OLD);
		newCache.kit(3, NEW);
		votes(100, 5, NEW);
		ModelMatcher m = new ModelMatcher(oldCache, newCache);
		assertNoProposal(m.objectModel(OLD), Status.CHANGED);
		assertNoProposal(m.objectModel(1), Status.IDENTICAL);
	}
}
