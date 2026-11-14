package com.magnaboy.tools.cachediff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An in-memory {@link CacheView} for tests. */
final class FakeCache implements CacheView {
	private final Map<Integer, ModelState> models = new TreeMap<>();
	private final Map<Integer, int[]> kits = new HashMap<>();
	private final Map<Integer, NpcInfo> npcs = new HashMap<>();
	private final Map<Integer, SequenceInfo> sequences = new HashMap<>();

	FakeCache model(int id, String hash, int... colors) {
		models.put(id, ModelState.ok(hash, colors));
		return this;
	}

	FakeCache undecodable(int id) {
		models.put(id, ModelState.undecodable("BufferUnderflowException"));
		return this;
	}

	FakeCache kit(int id, int... modelIds) {
		kits.put(id, modelIds);
		return this;
	}

	FakeCache npc(int id, int... modelIds) {
		npcs.put(id, new NpcInfo("npc" + id, modelIds, -1, -1));
		return this;
	}

	FakeCache animatedNpc(int id, int stand, int walk) {
		npcs.put(id, new NpcInfo("npc" + id, new int[0], stand, walk));
		return this;
	}

	FakeCache sequence(int id, int skeleton, int... frameLengths) {
		sequences.put(id, new SequenceInfo(frameLengths, skeleton, -1, 0));
		return this;
	}

	@Override
	public ModelState model(int id) {
		return models.getOrDefault(id, ModelState.absent());
	}

	@Override
	public List<Integer> modelsWithHash(String hash) {
		List<Integer> out = new ArrayList<>();
		models.forEach((id, m) -> {
			if (m.ok() && m.hash.equals(hash)) {
				out.add(id);
			}
		});
		return out;
	}

	@Override
	public Map<Integer, int[]> kits() {
		return kits;
	}

	@Override
	public Map<Integer, NpcInfo> npcs() {
		return npcs;
	}

	@Override
	public SequenceInfo sequence(int id) {
		return sequences.get(id);
	}
}
