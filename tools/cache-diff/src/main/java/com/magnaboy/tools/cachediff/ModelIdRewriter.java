package com.magnaboy.tools.cachediff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Rewrites the {@code modelIds} numbers of citizen entities in a region file's text.
 *
 * <p>Only the digits of each replaced number change; whitespace, key order, line endings, scenery
 * entities, merged objects and recolour arrays are left exactly as they were. Each original number
 * is looked up in the map once, so chains such as {@code a->b, b->c} turn {@code a} into {@code b}
 * (never into {@code c}).
 */
final class ModelIdRewriter {
	private ModelIdRewriter() {
	}

	static final class Edit {
		final int line;
		final int oldId;
		final int newId;
		final String entity;

		Edit(int line, int oldId, int newId, String entity) {
			this.line = line;
			this.oldId = oldId;
			this.newId = newId;
			this.entity = entity;
		}
	}

	static final class Result {
		final String text;
		final List<Edit> edits;

		Result(String text, List<Edit> edits) {
			this.text = text;
			this.edits = edits;
		}
	}

	private static final class Replacement {
		final Json.Num number;
		final int newId;
		final String entity;

		Replacement(Json.Num number, int newId, String entity) {
			this.number = number;
			this.newId = newId;
			this.entity = entity;
		}
	}

	static Result rewrite(String fileName, String text, Map<Integer, Integer> map) throws Json.ParseException {
		List<Replacement> replacements = new ArrayList<>();
		for (RegionData.Entity e : RegionData.entities(fileName, Json.parse(text))) {
			if (!e.citizen || e.modelIds == null) {
				continue;
			}
			for (Json.Value v : e.modelIds.items) {
				if (!(v instanceof Json.Num) || !((Json.Num) v).isInt()) {
					continue;
				}
				Integer to = map.get(((Json.Num) v).intValue());
				if (to != null && to != ((Json.Num) v).intValue()) {
					replacements.add(new Replacement((Json.Num) v, to, e.label()));
				}
			}
		}
		replacements.sort(Comparator.comparingInt(r -> r.number.start));

		StringBuilder out = new StringBuilder(text.length() + 16);
		List<Edit> edits = new ArrayList<>();
		int copied = 0;
		int line = 1;
		for (Replacement r : replacements) {
			for (int i = copied; i < r.number.start; i++) {
				if (text.charAt(i) == '\n') {
					line++;
				}
			}
			out.append(text, copied, r.number.start).append(r.newId);
			copied = r.number.end;
			edits.add(new Edit(line, r.number.intValue(), r.newId, r.entity));
		}
		out.append(text, copied, text.length());
		return new Result(out.toString(), edits);
	}

	/** A unified-diff-like preview: each edited line before and after. */
	static List<String> preview(String fileName, String before, Result result) {
		List<String> out = new ArrayList<>();
		if (result.edits.isEmpty()) {
			return out;
		}
		String[] oldLines = before.split("\n", -1);
		String[] newLines = result.text.split("\n", -1);
		out.add("--- " + fileName);
		out.add("+++ " + fileName);
		TreeSet<Integer> lines = new TreeSet<>();
		for (Edit e : result.edits) {
			lines.add(e.line);
		}
		String lastEntity = null;
		for (int line : lines) {
			String entity = null;
			for (Edit e : result.edits) {
				if (e.line == line) {
					entity = e.entity;
					break;
				}
			}
			if (!entity.equals(lastEntity)) {
				out.add("@@ " + entity + " @@");
				lastEntity = entity;
			}
			out.add(String.format("-%5d %s", line, stripCr(oldLines[line - 1])));
			out.add(String.format("+%5d %s", line, stripCr(newLines[line - 1])));
		}
		return out;
	}

	private static String stripCr(String s) {
		return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
	}
}
