package com.magnaboy.tools.cachediff;

import com.magnaboy.tools.cachediff.CacheView.ModelState;
import com.magnaboy.tools.cachediff.CacheView.NpcInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides, for a model id used by the plugin's data, whether it changed between an old and a new
 * cache and which id replaces it. Pure logic over {@link CacheView}.
 *
 * <p>Rules, in order:
 * <ol>
 * <li>Old id absent or undecodable: {@code not-in-old}, no proposal.</li>
 * <li>Same hash at the same id: {@code identical}. Never remapped, whatever other evidence says.</li>
 * <li>Exact geometry move: exactly one other new id carries the old hash, and that id did not
 * already carry it in the old cache: {@code moved}.</li>
 * <li>Changed in place: a kit maps the id to itself, or the id stays at its position in more than
 * half of the NPCs (present in both caches) that used it: {@code changed-in-place}.</li>
 * <li>Kit mapping to one other id, and the NPC votes agree with it (it is the untied top vote,
 * {@code kit+votes}) or there are
 * no NPC votes at all ({@code kit-only}): {@code replaced}.</li>
 * <li>No kit evidence, no NPC in the new cache uses the old id any more, and one target has at
 * least {@link #VOTES_ONLY_MIN_PERCENT}% of the votes, cast by at least
 * {@link #VOTES_ONLY_MIN_VOTERS} NPCs
 * ({@code votes-only}): {@code replaced}.</li>
 * <li>Otherwise {@code missing-in-new} if the id is gone, else {@code unresolved}.</li>
 * </ol>
 * A vote is one position in the model list of an NPC that exists in both caches with a model list of
 * the same length: old model at that position votes for the new model at that position.
 */
final class ModelMatcher {
	/** Votes-only proposals need the top target to hold at least this percentage of all votes. */
	static final int VOTES_ONLY_MIN_PERCENT = 90;
	/** Votes-only proposals need at least this many distinct NPCs voting. */
	static final int VOTES_ONLY_MIN_VOTERS = 3;

	enum Status {
		IDENTICAL("identical"),
		CHANGED_IN_PLACE("changed-in-place"),
		MOVED("moved"),
		REPLACED("replaced"),
		MISSING_IN_NEW("missing-in-new"),
		NOT_IN_OLD("not-in-old"),
		UNRESOLVED("unresolved"),
		/** Object models only: hash differs or a side is missing. */
		CHANGED("changed");

		final String label;

		Status(String label) {
			this.label = label;
		}
	}

	enum Rule {
		GEOMETRY_MOVE("geometry-move"),
		KIT_AND_VOTES("kit+votes"),
		KIT_ONLY("kit-only"),
		VOTES_ONLY("votes-only");

		final String label;

		Rule(String label) {
			this.label = label;
		}
	}

	static final class Verdict {
		final int id;
		final Status status;
		final Integer proposal;
		final Rule rule;
		/** Target id to vote count, highest first. */
		final Map<Integer, Integer> votes;
		/** Target id to the kits mapping the old id to it. */
		final Map<Integer, List<Integer>> kitTargets;
		/** NPCs present in both caches whose old model list contains the id. */
		final int npcUsers;
		/** NPCs in the new cache whose model list contains the id. */
		final int newNpcUsers;
		/** Other new ids carrying the old geometry. */
		final List<Integer> geometryMatches;
		final String note;

		Verdict(int id, Status status, Integer proposal, Rule rule, Map<Integer, Integer> votes,
			Map<Integer, List<Integer>> kitTargets, int npcUsers, int newNpcUsers, List<Integer> geometryMatches, String note) {
			this.id = id;
			this.status = status;
			this.proposal = proposal;
			this.rule = rule;
			this.votes = votes;
			this.kitTargets = kitTargets;
			this.npcUsers = npcUsers;
			this.newNpcUsers = newNpcUsers;
			this.geometryMatches = geometryMatches;
			this.note = note;
		}

		int totalVotes() {
			return sumOf(votes);
		}

		/** Human-readable evidence for the report. */
		String evidence() {
			List<String> parts = new ArrayList<>();
			if (note != null) {
				parts.add(note);
			}
			if (!kitTargets.isEmpty()) {
				List<String> kits = new ArrayList<>();
				kitTargets.forEach((target, ids) -> kits.add("kit " + join(ids) + " -> " + target));
				parts.add(String.join("; ", kits));
			}
			if (!votes.isEmpty()) {
				List<String> v = new ArrayList<>();
				int shown = 0;
				for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
					if (shown++ == 4) {
						v.add("...");
						break;
					}
					v.add((e.getKey() == id ? "same" : String.valueOf(e.getKey())) + " x" + e.getValue());
				}
				parts.add("votes " + String.join(", ", v));
			}
			if (npcUsers > 0 || newNpcUsers > 0) {
				parts.add("npcs " + npcUsers + " old / " + newNpcUsers + " new");
			}
			if (!geometryMatches.isEmpty()) {
				parts.add("geometry at " + join(geometryMatches));
			}
			return String.join(" | ", parts);
		}

		private static String join(List<Integer> ids) {
			List<String> s = new ArrayList<>();
			for (int i : ids) {
				s.add(String.valueOf(i));
			}
			return String.join(",", s);
		}
	}

	private final CacheView oldCache;
	private final CacheView newCache;
	private final Map<Integer, Map<Integer, Integer>> votes = new HashMap<>();
	private final Map<Integer, Integer> voters = new HashMap<>();
	private final Map<Integer, Integer> npcUsers = new HashMap<>();
	private final Map<Integer, Integer> newNpcUsers = new HashMap<>();
	private final Map<Integer, Map<Integer, List<Integer>>> kitTargets = new HashMap<>();

	private boolean indexed;

	ModelMatcher(CacheView oldCache, CacheView newCache) {
		this.oldCache = oldCache;
		this.newCache = newCache;
	}

	private void index() {
		if (indexed) {
			return;
		}
		indexed = true;
		Map<Integer, NpcInfo> newNpcs = newCache.npcs();
		for (Map.Entry<Integer, NpcInfo> e : oldCache.npcs().entrySet()) {
			NpcInfo now = newNpcs.get(e.getKey());
			if (now == null) {
				continue;
			}
			int[] before = e.getValue().models;
			for (int m : distinct(before)) {
				npcUsers.merge(m, 1, Integer::sum);
			}
			if (before.length != now.models.length) {
				continue;
			}
			for (int i = 0; i < before.length; i++) {
				votes.computeIfAbsent(before[i], k -> new HashMap<>()).merge(now.models[i], 1, Integer::sum);
			}
			for (int m : distinct(before)) {
				voters.merge(m, 1, Integer::sum);
			}
		}
		for (NpcInfo n : newNpcs.values()) {
			for (int m : distinct(n.models)) {
				newNpcUsers.merge(m, 1, Integer::sum);
			}
		}
		Map<Integer, int[]> newKits = newCache.kits();
		for (Map.Entry<Integer, int[]> e : new TreeMap<>(oldCache.kits()).entrySet()) {
			int[] now = newKits.get(e.getKey());
			int[] before = e.getValue();
			if (now == null || now.length != before.length) {
				continue;
			}
			for (int i = 0; i < before.length; i++) {
				kitTargets.computeIfAbsent(before[i], k -> new TreeMap<>())
					.computeIfAbsent(now[i], k -> new ArrayList<>()).add(e.getKey());
			}
		}
	}

	private static Set<Integer> distinct(int[] ids) {
		Set<Integer> s = new HashSet<>();
		for (int i : ids) {
			s.add(i);
		}
		return s;
	}

	/** Verdict for a model id used by a citizen. */
	Verdict citizenModel(int id) {
		index();
		Map<Integer, Integer> v = sortedVotes(votes.getOrDefault(id, Collections.emptyMap()));
		Map<Integer, List<Integer>> kits = kitTargets.getOrDefault(id, Collections.emptyMap());
		int users = npcUsers.getOrDefault(id, 0);
		int newUsers = newNpcUsers.getOrDefault(id, 0);
		ModelState before = oldCache.model(id);
		ModelState after = newCache.model(id);

		if (!before.ok()) {
			String note = before.kind == ModelState.Kind.ABSENT
				? "absent from old cache"
				: "old model undecodable (" + before.error + ")";
			return new Verdict(id, Status.NOT_IN_OLD, null, null, v, kits, users, newUsers, Collections.emptyList(), note);
		}
		if (after.ok() && after.hash.equals(before.hash)) {
			return new Verdict(id, Status.IDENTICAL, null, null, v, kits, users, newUsers, Collections.emptyList(), null);
		}

		List<Integer> moved = new ArrayList<>();
		for (int other : newCache.modelsWithHash(before.hash)) {
			// The target must not have had this geometry already; an undecodable old model is unknown, so not a move.
			ModelState otherBefore = oldCache.model(other);
			boolean newThere = otherBefore.kind == ModelState.Kind.ABSENT || (otherBefore.ok() && !otherBefore.hash.equals(before.hash));
			if (other != id && newThere) {
				moved.add(other);
			}
		}
		Collections.sort(moved);
		if (moved.size() == 1) {
			return new Verdict(id, Status.MOVED, moved.get(0), Rule.GEOMETRY_MOVE, v, kits, users, newUsers, moved, null);
		}

		int sameVotes = v.getOrDefault(id, 0);
		Integer kitTarget = kits.size() == 1 ? kits.keySet().iterator().next() : null;
		if ((kitTarget != null && kitTarget == id) || sameVotes * 2 > users) {
			return new Verdict(id, Status.CHANGED_IN_PLACE, null, null, v, kits, users, newUsers, moved, null);
		}

		Integer topVote = v.isEmpty() ? null : v.keySet().iterator().next();
		boolean tiedTop = false;
		if (v.size() > 1) {
			List<Integer> counts = new ArrayList<>(v.values());
			tiedTop = counts.get(0).equals(counts.get(1));
		}
		if (kitTarget != null && topVote == null) {
			return new Verdict(id, Status.REPLACED, kitTarget, Rule.KIT_ONLY, v, kits, users, newUsers, moved, null);
		}
		if (kitTarget != null && kitTarget.equals(topVote) && !tiedTop) {
			return new Verdict(id, Status.REPLACED, kitTarget, Rule.KIT_AND_VOTES, v, kits, users, newUsers, moved, null);
		}
		if (kits.isEmpty() && topVote != null && topVote != id && newUsers == 0) {
			int total = sumOf(v);
			if (voters.getOrDefault(id, 0) >= VOTES_ONLY_MIN_VOTERS && v.get(topVote) * 100 >= VOTES_ONLY_MIN_PERCENT * total) {
				return new Verdict(id, Status.REPLACED, topVote, Rule.VOTES_ONLY, v, kits, users, newUsers, moved, null);
			}
		}

		String note = null;
		if (moved.size() > 1) {
			note = "old geometry at several new ids";
		} else if (kits.size() > 1) {
			note = "kits disagree";
		} else if (tiedTop) {
			note = "top votes tied";
		} else if (kitTarget != null) {
			note = "kit and votes disagree";
		}
		Status status = after.kind == ModelState.Kind.ABSENT ? Status.MISSING_IN_NEW : Status.UNRESOLVED;
		return new Verdict(id, status, null, null, v, kits, users, newUsers, moved, note);
	}

	/** Verdict for a scenery or merged-object model id: identical or changed only, never a proposal. */
	Verdict objectModel(int id) {
		ModelState before = oldCache.model(id);
		ModelState after = newCache.model(id);
		if (before.ok() && after.ok() && before.hash.equals(after.hash)) {
			return new Verdict(id, Status.IDENTICAL, null, null, Collections.emptyMap(), Collections.emptyMap(), 0, 0, Collections.emptyList(), null);
		}
		String note;
		if (!before.ok()) {
			note = before.kind == ModelState.Kind.ABSENT ? "absent from old cache" : "old model undecodable (" + before.error + ")";
		} else if (!after.ok()) {
			note = after.kind == ModelState.Kind.ABSENT ? "absent from new cache" : "new model undecodable (" + after.error + ")";
		} else {
			note = "geometry or colours differ";
		}
		return new Verdict(id, Status.CHANGED, null, null, Collections.emptyMap(), Collections.emptyMap(), 0, 0, Collections.emptyList(), note);
	}

	private static int sumOf(Map<Integer, Integer> v) {
		int total = 0;
		for (int n : v.values()) {
			total += n;
		}
		return total;
	}

	private static Map<Integer, Integer> sortedVotes(Map<Integer, Integer> raw) {
		List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(raw.entrySet());
		entries.sort((a, b) -> b.getValue().equals(a.getValue())
			? Integer.compare(a.getKey(), b.getKey())
			: Integer.compare(b.getValue(), a.getValue()));
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : entries) {
			out.put(e.getKey(), e.getValue());
		}
		return out;
	}
}
