package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** {@code apply}: rewrite citizen {@code modelIds} from an id map. Dry run unless {@code --write}. */
final class ApplyCommand {
	private final PrintStream out;

	ApplyCommand(PrintStream out) {
		this.out = out;
	}

	int run(CliArgs args) throws CliArgs.UsageException, IOException, MapFile.MapFileException, Json.ParseException {
		Path mapFile = args.required("--map");
		Path dataDir = args.optional("--data", CacheDiff.DEFAULT_DATA);
		boolean write = args.flag("--write");
		if (!Files.isRegularFile(mapFile)) {
			throw new CliArgs.UsageException("map file not found: " + mapFile.toAbsolutePath());
		}
		if (!Files.isDirectory(dataDir)) {
			throw new CliArgs.UsageException("data directory not found: " + dataDir.toAbsolutePath() + " (run from the repository root or pass --data)");
		}
		Map<Integer, Integer> map = MapFile.read(mapFile);
		out.println((write ? "WRITE" : "DRY RUN") + ": " + map.size() + " map entries, data " + dataDir);
		Set<Integer> chained = new TreeSet<>(map.values());
		chained.retainAll(map.keySet());
		if (!chained.isEmpty()) {
			out.println("note: the map chains through " + chained + "; each run maps every id once,"
				+ " so run --write only once per map (a second run would map those ids again)");
		}

		Set<Integer> used = new HashSet<>();
		int changedFiles = 0;
		int changedIds = 0;
		Map<Path, String> rewritten = new LinkedHashMap<>();
		for (Path file : RegionData.files(dataDir)) {
			String name = file.getFileName().toString();
			String before = RegionData.read(file);
			ModelIdRewriter.Result result;
			try {
				result = ModelIdRewriter.rewrite(name, before, map);
			} catch (Json.ParseException e) {
				throw new Json.ParseException(name + ": " + e.getMessage());
			}
			if (result.edits.isEmpty()) {
				continue;
			}
			Set<String> entities = new HashSet<>();
			for (ModelIdRewriter.Edit e : result.edits) {
				used.add(e.oldId);
				entities.add(e.entity);
			}
			changedFiles++;
			changedIds += result.edits.size();
			out.println();
			out.println(name + ": " + result.edits.size() + " ids in " + entities.size() + " citizens");
			for (String line : ModelIdRewriter.preview(name, before, result)) {
				out.println(line);
			}
			Json.parse(result.text);
			rewritten.put(file, result.text);
		}
		if (write) {
			for (Map.Entry<Path, String> e : rewritten.entrySet()) {
				Files.write(e.getKey(), RegionData.bytes(e.getValue()));
			}
		}
		Set<Integer> unused = new TreeSet<>(map.keySet());
		unused.removeAll(used);
		out.println();
		out.println(changedIds + " ids in " + changedFiles + " files " + (write ? "rewritten" : "would change"));
		if (!unused.isEmpty()) {
			out.println("map entries not used by any citizen: " + unused);
		}
		if (!write) {
			out.println("Dry run: nothing written. Re-run with --write to apply.");
		}
		return CacheDiff.OK;
	}
}
