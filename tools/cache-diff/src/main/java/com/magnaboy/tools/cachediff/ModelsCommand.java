package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code models}: status and proposed replacement for every model id the region data uses. */
final class ModelsCommand {
	private final PrintStream out;

	ModelsCommand(PrintStream out) {
		this.out = out;
	}

	int run(CliArgs args) throws CliArgs.UsageException, GameCache.CacheException, IOException, Json.ParseException {
		Path oldDir = args.required("--old");
		Path newDir = args.required("--new");
		Path dataDir = args.optional("--data", CacheDiff.DEFAULT_DATA);
		Path outFile = args.optional("--out", null);
		if (!Files.isDirectory(dataDir)) {
			throw new CliArgs.UsageException("data directory not found: " + dataDir.toAbsolutePath() + " (run from the repository root or pass --data)");
		}
		List<RegionData.Entity> entities = RegionData.load(dataDir);

		Map<Integer, Integer> citizenUses = new TreeMap<>();
		Map<Integer, Integer> objectUses = new TreeMap<>();
		for (RegionData.Entity e : entities) {
			for (int id : e.models) {
				(e.citizen ? citizenUses : objectUses).merge(id, 1, Integer::sum);
			}
			for (int id : e.mergedObjectIds) {
				objectUses.merge(id, 1, Integer::sum);
			}
		}

		try (GameCache oldCache = GameCache.open(oldDir); GameCache newCache = GameCache.open(newDir)) {
			out.println("old cache: " + oldDir);
			out.println("new cache: " + newDir);
			out.println("data:      " + dataDir + " (" + entities.size() + " entities)");
			out.println();

			ModelMatcher matcher = new ModelMatcher(oldCache, newCache);
			List<ModelMatcher.Verdict> verdicts = new ArrayList<>();
			for (int id : citizenUses.keySet()) {
				verdicts.add(matcher.citizenModel(id));
			}
			Map<Integer, Integer> map = new TreeMap<>();
			List<ModelMatcher.Verdict> proposals = new ArrayList<>();
			for (ModelMatcher.Verdict v : verdicts) {
				if (v.proposal != null) {
					map.put(v.id, v.proposal);
					proposals.add(v);
				}
			}
			printCitizenModels(verdicts, citizenUses);
			printObjectModels(matcher, objectUses);
			printRecolors(RecolorCheck.check(entities, map, oldCache, newCache));

			if (outFile != null) {
				Path parent = outFile.toAbsolutePath().getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.write(outFile, MapFile.format(map).getBytes(StandardCharsets.UTF_8));
				Path evidence = MapFile.evidencePath(outFile);
				Files.write(evidence, MapFile.formatEvidence(proposals).getBytes(StandardCharsets.UTF_8));
				out.println();
				out.println("wrote " + map.size() + " proposals to " + outFile + " (evidence: " + evidence + ")");
			}
		}
		return CacheDiff.OK;
	}

	private void printCitizenModels(List<ModelMatcher.Verdict> verdicts, Map<Integer, Integer> uses) {
		out.println("== Citizen model ids (" + verdicts.size() + ")");
		out.println(String.format("%8s  %-16s  %8s  %-13s  %4s  %s", "id", "status", "proposal", "rule", "uses", "evidence"));
		Map<ModelMatcher.Status, Integer> byStatus = new EnumMap<>(ModelMatcher.Status.class);
		Map<ModelMatcher.Rule, Integer> byRule = new EnumMap<>(ModelMatcher.Rule.class);
		List<Integer> identical = new ArrayList<>();
		for (ModelMatcher.Verdict v : verdicts) {
			byStatus.merge(v.status, 1, Integer::sum);
			if (v.rule != null) {
				byRule.merge(v.rule, 1, Integer::sum);
			}
			if (v.status == ModelMatcher.Status.IDENTICAL) {
				identical.add(v.id);
				continue;
			}
			out.println(String.format("%8d  %-16s  %8s  %-13s  %4d  %s", v.id, v.status.label,
				v.proposal == null ? "-" : v.proposal, v.rule == null ? "-" : v.rule.label, uses.get(v.id), v.evidence()));
		}
		out.println("identical (" + identical.size() + "): " + wrap(identical));
		out.println();
		List<String> s = new ArrayList<>();
		byStatus.forEach((k, n) -> s.add(k.label + " " + n));
		out.println("by status: " + String.join(", ", s));
		List<String> r = new ArrayList<>();
		byRule.forEach((k, n) -> r.add(k.label + " " + n));
		out.println("proposals by rule: " + (r.isEmpty() ? "none" : String.join(", ", r))
			+ "  (kit-only and votes-only rest on weaker evidence: check those citizens in-game)");
		out.println();
	}

	private void printObjectModels(ModelMatcher matcher, Map<Integer, Integer> uses) {
		out.println("== Scenery and merged-object model ids (" + uses.size() + ", no proposals)");
		int identical = 0;
		for (int id : uses.keySet()) {
			ModelMatcher.Verdict v = matcher.objectModel(id);
			if (v.status == ModelMatcher.Status.IDENTICAL) {
				identical++;
			} else {
				out.println(String.format("%8d  %-16s  uses %d  %s", id, v.status.label, uses.get(id), v.note));
			}
		}
		out.println("identical: " + identical);
		out.println();
	}

	private void printRecolors(List<RecolorCheck.Issue> issues) {
		List<RecolorCheck.Issue> lost = new ArrayList<>();
		List<RecolorCheck.Issue> never = new ArrayList<>();
		for (RecolorCheck.Issue i : issues) {
			(i.lost ? lost : never).add(i);
		}
		out.println("== Recolour colours that no longer exist on the citizen's models (after the proposals): " + lost.size());
		for (RecolorCheck.Issue i : lost) {
			out.println("  " + i);
		}
		out.println();
		out.println("== Recolour colours not found on the citizen's models in either cache"
			+ " (already ineffective, or old model undecodable): " + never.size());
		for (RecolorCheck.Issue i : never) {
			out.println("  " + i);
		}
	}

	private static String wrap(List<Integer> ids) {
		StringBuilder sb = new StringBuilder();
		int lineLength = 0;
		for (int i = 0; i < ids.size(); i++) {
			String s = ids.get(i) + (i + 1 < ids.size() ? ", " : "");
			if (lineLength + s.length() > 100) {
				sb.append("\n    ");
				lineLength = 0;
			}
			sb.append(s);
			lineLength += s.length();
		}
		return sb.toString();
	}
}
