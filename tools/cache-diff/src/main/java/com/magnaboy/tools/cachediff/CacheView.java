package com.magnaboy.tools.cachediff;

import java.util.List;
import java.util.Map;

/**
 * What the matching logic needs from one game cache, in plain Java types. {@link GameCache} reads a
 * real cache; tests supply synthetic data.
 */
interface CacheView {
	/** The model with this id: absent, undecodable, or its geometry hash and face colours. */
	ModelState model(int id);

	/** Every model id in this cache whose hash equals {@code hash}. */
	List<Integer> modelsWithHash(String hash);

	/** Player-kit (identity kit) id to its body-part model ids. */
	Map<Integer, int[]> kits();

	/** NPC definitions by id. */
	Map<Integer, NpcInfo> npcs();

	/** The animation sequence with this id, or {@code null} if absent. */
	SequenceInfo sequence(int id);

	final class ModelState {
		enum Kind {
			ABSENT,
			UNDECODABLE,
			OK
		}

		final Kind kind;
		final String hash;
		final int[] colors;
		final String error;

		private ModelState(Kind kind, String hash, int[] colors, String error) {
			this.kind = kind;
			this.hash = hash;
			this.colors = colors;
			this.error = error;
		}

		static ModelState absent() {
			return new ModelState(Kind.ABSENT, null, new int[0], null);
		}

		static ModelState undecodable(String error) {
			return new ModelState(Kind.UNDECODABLE, null, new int[0], error);
		}

		/** @param colors distinct face colours as unsigned 16-bit values */
		static ModelState ok(String hash, int[] colors) {
			return new ModelState(Kind.OK, hash, colors, null);
		}

		boolean ok() {
			return kind == Kind.OK;
		}
	}

	final class NpcInfo {
		final String name;
		final int[] models;
		final int standAnimation;
		final int walkAnimation;

		NpcInfo(String name, int[] models, int standAnimation, int walkAnimation) {
			this.name = name;
			this.models = models == null ? new int[0] : models;
			this.standAnimation = standAnimation;
			this.walkAnimation = walkAnimation;
		}
	}

	final class SequenceInfo {
		/** Ticks (client frames) per frame; empty for sequences driven by a maya animation. */
		final int[] frameLengths;
		/** Skeleton (frame map) id of the first frame, or -1 if unknown. */
		final int skeleton;
		/** Maya animation id, or -1 for classic frame-based sequences. */
		final int mayaId;
		final int mayaLength;

		SequenceInfo(int[] frameLengths, int skeleton, int mayaId, int mayaLength) {
			this.frameLengths = frameLengths == null ? new int[0] : frameLengths;
			this.skeleton = skeleton;
			this.mayaId = mayaId;
			this.mayaLength = mayaLength;
		}

		int duration() {
			int total = 0;
			for (int l : frameLengths) {
				total += l;
			}
			return total;
		}
	}
}
