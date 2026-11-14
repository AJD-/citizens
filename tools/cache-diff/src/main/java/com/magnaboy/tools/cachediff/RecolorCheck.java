package com.magnaboy.tools.cachediff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds recolour "find" colours that do not exist on any of a citizen's (new) models, so the
 * recolour silently does nothing. Colours compare as unsigned 16-bit values.
 */
final class RecolorCheck {
	private RecolorCheck() {
	}

	static final class Issue {
		final RegionData.Entity entity;
		final int color;
		final int[] models;
		/** The colour was on the citizen's models in the old cache: the update lost it. */
		final boolean lost;

		Issue(RegionData.Entity entity, int color, int[] models, boolean lost) {
			this.entity = entity;
			this.color = color;
			this.models = models;
			this.lost = lost;
		}

		@Override
		public String toString() {
			return entity.label() + ": colour " + color + " not on models " + Arrays.toString(models);
		}
	}

	/**
	 * @param map proposed old-to-new ids, applied once to each model id before checking
	 */
	static List<Issue> check(List<RegionData.Entity> entities, Map<Integer, Integer> map, CacheView oldCache, CacheView newCache) {
		List<Issue> out = new ArrayList<>();
		for (RegionData.Entity e : entities) {
			if (!e.citizen || e.recolorFind.length == 0) {
				continue;
			}
			int[] models = new int[e.models.length];
			Set<Integer> colors = new HashSet<>();
			Set<Integer> oldColors = new HashSet<>();
			for (int i = 0; i < models.length; i++) {
				models[i] = map.getOrDefault(e.models[i], e.models[i]);
				add(colors, newCache.model(models[i]).colors);
				add(oldColors, oldCache.model(e.models[i]).colors);
			}
			for (int find : e.recolorFind) {
				int c = find & 0xFFFF;
				if (!colors.contains(c)) {
					out.add(new Issue(e, find, models, oldColors.contains(c)));
				}
			}
		}
		return out;
	}

	private static void add(Set<Integer> set, int[] values) {
		for (int v : values) {
			set.add(v);
		}
	}
}
