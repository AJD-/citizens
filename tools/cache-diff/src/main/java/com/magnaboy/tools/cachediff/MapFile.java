package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The id map exchanged between {@code models --out} and {@code apply --map}: a flat JSON object
 * {@code { "oldId": newId, ... }}. The evidence behind each entry goes to a sibling
 * {@code *.evidence.json} file that {@code apply} never reads.
 */
final class MapFile {
	private MapFile() {
	}

	static final class MapFileException extends Exception {
		MapFileException(String message) {
			super(message);
		}
	}

	static Map<Integer, Integer> read(Path file) throws IOException, MapFileException {
		Json.Value root;
		try {
			root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
		} catch (Json.ParseException e) {
			throw new MapFileException(file + ": " + e.getMessage());
		}
		if (!(root instanceof Json.Obj)) {
			throw new MapFileException(file + ": expected a JSON object { \"oldId\": newId, ... }");
		}
		Map<Integer, Integer> map = new TreeMap<>();
		for (Map.Entry<String, Json.Value> e : ((Json.Obj) root).members.entrySet()) {
			Integer key = Json.toInt(e.getKey());
			if (key == null || !(e.getValue() instanceof Json.Num) || !((Json.Num) e.getValue()).isInt()) {
				throw new MapFileException(file + ": entry \"" + e.getKey() + "\" is not an integer id mapped to an integer id");
			}
			map.put(key, ((Json.Num) e.getValue()).intValue());
		}
		return map;
	}

	static String format(Map<Integer, Integer> map) {
		List<String> lines = new ArrayList<>();
		new TreeMap<>(map).forEach((k, v) -> lines.add("  \"" + k + "\": " + v));
		return lines.isEmpty() ? "{}\n" : "{\n" + String.join(",\n", lines) + "\n}\n";
	}

	/** {@code map.json} becomes {@code map.evidence.json}; other names get the suffix appended. */
	static Path evidencePath(Path mapFile) {
		String name = mapFile.getFileName().toString();
		String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
		return mapFile.resolveSibling(base + ".evidence.json");
	}

	static String formatEvidence(List<ModelMatcher.Verdict> proposals) {
		List<String> entries = new ArrayList<>();
		for (ModelMatcher.Verdict v : proposals) {
			StringBuilder sb = new StringBuilder();
			sb.append("  \"").append(v.id).append("\": {\"to\": ").append(v.proposal)
				.append(", \"status\": \"").append(v.status.label)
				.append("\", \"rule\": \"").append(v.rule.label)
				.append("\", \"votes\": {");
			List<String> votes = new ArrayList<>();
			v.votes.forEach((target, n) -> votes.add("\"" + target + "\": " + n));
			sb.append(String.join(", ", votes)).append("}, \"totalVotes\": ").append(v.totalVotes())
				.append(", \"kits\": {");
			List<String> kits = new ArrayList<>();
			v.kitTargets.forEach((target, ids) -> kits.add("\"" + target + "\": " + ids));
			sb.append(String.join(", ", kits)).append("}, \"npcUsersOld\": ").append(v.npcUsers)
				.append(", \"npcUsersNew\": ").append(v.newNpcUsers)
				.append(", \"geometryMatches\": ").append(v.geometryMatches)
				.append(", \"geometryDuplicates\": ").append(v.geometryDuplicates)
				.append(", \"note\": ").append(v.note == null ? "null" : Json.quote(v.note)).append('}');
			entries.add(sb.toString());
		}
		return entries.isEmpty() ? "{}\n" : "{\n" + String.join(",\n", entries) + "\n}\n";
	}
}
