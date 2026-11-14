package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code anims}: unchanged / changed for every id in the AnimationID enum. Report only. */
final class AnimsCommand {
	private final PrintStream out;

	AnimsCommand(PrintStream out) {
		this.out = out;
	}

	int run(CliArgs args) throws CliArgs.UsageException, GameCache.CacheException, IOException {
		Path oldDir = args.required("--old");
		Path newDir = args.required("--new");
		Path enumFile = args.optional("--enum", CacheDiff.DEFAULT_ENUM);
		if (!Files.isRegularFile(enumFile)) {
			throw new CliArgs.UsageException("enum file not found: " + enumFile.toAbsolutePath() + " (run from the repository root or pass --enum)");
		}
		List<AnimationEnum.Constant> constants = AnimationEnum.parse(new String(Files.readAllBytes(enumFile), StandardCharsets.UTF_8));
		if (constants.isEmpty()) {
			throw new CliArgs.UsageException("no constants like Name(123) found in " + enumFile);
		}

		try (GameCache oldCache = GameCache.open(oldDir); GameCache newCache = GameCache.open(newDir)) {
			out.println("old cache: " + oldDir);
			out.println("new cache: " + newDir);
			out.println("enum:      " + enumFile + " (" + constants.size() + " constants)");
			out.println();
			AnimMatcher matcher = new AnimMatcher(oldCache, newCache);
			Map<Integer, AnimMatcher.Verdict> cache = new HashMap<>();
			List<String> changed = new ArrayList<>();
			List<String> absent = new ArrayList<>();
			out.println(String.format("%-22s  %6s  %-9s  %8s  %s", "constant", "id", "status", "proposal", "details"));
			for (AnimationEnum.Constant c : constants) {
				AnimMatcher.Verdict v = cache.computeIfAbsent(c.id, matcher::compare);
				String details = String.join("; ", v.differences);
				if (v.changed) {
					changed.add(c.name + "(" + c.id + ")" + (v.proposal != null ? " -> " + v.proposal : ""));
					List<String> votes = new ArrayList<>();
					v.votes.forEach((target, n) -> votes.add((target == c.id ? "same" : String.valueOf(target)) + " x" + n));
					details += " | npc stand/walk votes: " + (votes.isEmpty() ? "none" : String.join(", ", votes));
				} else if (AnimMatcher.ABSENT.equals(v.status)) {
					absent.add(c.name + "(" + c.id + ")");
				}
				out.println(String.format("%-22s  %6d  %-9s  %8s  %s", c.name, c.id, v.status,
					v.proposal == null ? "-" : v.proposal, details));
			}
			out.println();
			out.println("changed: " + changed.size() + (changed.isEmpty() ? "" : "  " + String.join(", ", changed)));
			if (!absent.isEmpty()) {
				out.println("absent from both caches: " + String.join(", ", absent));
			}
			out.println("Edit AnimationID.java by hand for the proposals you accept; check each in-game.");
		}
		return CacheDiff.OK;
	}
}
