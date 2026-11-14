package com.magnaboy.tools.cachediff;

import com.magnaboy.tools.cachediff.CacheView.NpcInfo;
import com.magnaboy.tools.cachediff.CacheView.SequenceInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares animation sequences between two caches and proposes replacements from NPC stand/walk
 * animations. Frame-archive ids are not compared: cache repacks renumber them without changing the
 * animation.
 */
final class AnimMatcher {
	static final String UNCHANGED = "unchanged";
	static final String CHANGED = "changed";
	static final String ABSENT = "absent";
	private static final int NO_ANIMATION = -1;

	static final class Verdict {
		final int id;
		/** {@link #UNCHANGED}, {@link #CHANGED}, or {@link #ABSENT} (in neither cache). */
		final String status;
		final boolean changed;
		final List<String> differences;
		final Integer proposal;
		/** Target id to vote count, highest first. */
		final Map<Integer, Integer> votes;

		Verdict(int id, String status, List<String> differences, Integer proposal, Map<Integer, Integer> votes) {
			this.id = id;
			this.status = status;
			this.changed = CHANGED.equals(status);
			this.differences = differences;
			this.proposal = proposal;
			this.votes = votes;
		}
	}

	private final CacheView oldCache;
	private final CacheView newCache;

	AnimMatcher(CacheView oldCache, CacheView newCache) {
		this.oldCache = oldCache;
		this.newCache = newCache;
	}

	Verdict compare(int id) {
		SequenceInfo before = oldCache.sequence(id);
		SequenceInfo after = newCache.sequence(id);
		if (before == null && after == null) {
			return new Verdict(id, ABSENT, Collections.singletonList("absent from both caches"), null, new LinkedHashMap<>());
		}
		List<String> diffs = differences(before, after);
		if (diffs.isEmpty()) {
			return new Verdict(id, UNCHANGED, diffs, null, new LinkedHashMap<>());
		}
		Map<Integer, Integer> votes = votes(id);
		Integer proposal = null;
		if (!votes.isEmpty()) {
			int top = votes.keySet().iterator().next();
			int total = votes.values().stream().mapToInt(Integer::intValue).sum();
			if (top != id && votes.get(top) * 2 > total) {
				proposal = top;
			}
		}
		return new Verdict(id, CHANGED, diffs, proposal, votes);
	}

	/** Differences between two sequences; at most one of them may be null. */
	static List<String> differences(SequenceInfo a, SequenceInfo b) {
		List<String> out = new ArrayList<>();
		if (a == null || b == null) {
			out.add(a == null ? "absent from old cache" : "absent from new cache");
			return out;
		}
		if (a.frameLengths.length != b.frameLengths.length) {
			out.add("frames " + a.frameLengths.length + " -> " + b.frameLengths.length);
		} else if (!Arrays.equals(a.frameLengths, b.frameLengths)) {
			out.add("frame lengths " + Arrays.toString(a.frameLengths) + " -> " + Arrays.toString(b.frameLengths));
		}
		if (a.duration() != b.duration()) {
			out.add("duration " + a.duration() + " -> " + b.duration());
		}
		if (a.skeleton != b.skeleton) {
			out.add("skeleton " + a.skeleton + " -> " + b.skeleton);
		}
		if ((a.mayaId == -1) != (b.mayaId == -1)) {
			out.add(a.mayaId == -1 ? "now a maya animation" : "no longer a maya animation");
		} else if (a.mayaLength != b.mayaLength) {
			out.add("maya length " + a.mayaLength + " -> " + b.mayaLength);
		}
		return out;
	}

	/**
	 * NPCs in both caches whose stand (walk) animation was {@code id} vote for their new stand (walk)
	 * animation. An NPC that now has no animation (-1) casts no vote.
	 */
	private Map<Integer, Integer> votes(int id) {
		Map<Integer, Integer> raw = new HashMap<>();
		Map<Integer, NpcInfo> newNpcs = newCache.npcs();
		for (Map.Entry<Integer, NpcInfo> e : oldCache.npcs().entrySet()) {
			NpcInfo now = newNpcs.get(e.getKey());
			if (now == null) {
				continue;
			}
			if (e.getValue().standAnimation == id && now.standAnimation != NO_ANIMATION) {
				raw.merge(now.standAnimation, 1, Integer::sum);
			}
			if (e.getValue().walkAnimation == id && now.walkAnimation != NO_ANIMATION) {
				raw.merge(now.walkAnimation, 1, Integer::sum);
			}
		}
		List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(raw.entrySet());
		entries.sort((x, y) -> y.getValue().equals(x.getValue())
			? Integer.compare(x.getKey(), y.getKey())
			: Integer.compare(y.getValue(), x.getValue()));
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> en : entries) {
			out.put(en.getKey(), en.getValue());
		}
		return out;
	}
}
