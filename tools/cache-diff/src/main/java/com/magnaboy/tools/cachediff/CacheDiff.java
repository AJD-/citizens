package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;

/**
 * Maintainer tool: compares two game caches for the model and animation ids used by the plugin's
 * data, and rewrites region data from an id map. See {@code tools/cache-diff/README.md}.
 */
public final class CacheDiff {
	static final int OK = 0;
	static final int FAILED = 1;
	static final int USAGE = 2;

	static final String DEFAULT_DATA = "src/main/resources/RegionData";
	static final String DEFAULT_ENUM = "src/main/java/com/magnaboy/AnimationID.java";

	static final String USAGE_TEXT = String.join("\n",
		"usage: cache-diff <command> [options]",
		"  models --old <cacheDir> --new <cacheDir> [--data <RegionData dir>] [--out <map.json>]",
		"  anims  --old <cacheDir> --new <cacheDir> [--enum <AnimationID.java>]",
		"  apply  --map <map.json> [--data <RegionData dir>] [--write]",
		"Relative paths resolve against the current directory (the repository root under gradle run).");

	private CacheDiff() {
	}

	public static void main(String[] args) {
		System.exit(run(args, System.out, System.err));
	}

	static int run(String[] args, PrintStream out, PrintStream err) {
		if (args.length == 0) {
			err.println("cache-diff: no command (run 'help' for usage)");
			return USAGE;
		}
		try {
			switch (args[0]) {
				case "models":
					return new ModelsCommand(out).run(CliArgs.parse(args,
						Arrays.asList("--old", "--new", "--data", "--out"), Collections.emptyList()));
				case "anims":
					return new AnimsCommand(out).run(CliArgs.parse(args,
						Arrays.asList("--old", "--new", "--enum"), Collections.emptyList()));
				case "apply":
					return new ApplyCommand(out).run(CliArgs.parse(args,
						Arrays.asList("--map", "--data"), Collections.singletonList("--write")));
				case "help":
				case "-h":
				case "--help":
					out.println(USAGE_TEXT);
					return OK;
				default:
					err.println("cache-diff: unknown command: " + args[0] + " (run 'help' for usage)");
					return USAGE;
			}
		} catch (CliArgs.UsageException e) {
			err.println("cache-diff: " + e.getMessage() + " (run 'help' for usage)");
			return USAGE;
		} catch (GameCache.CacheException | MapFile.MapFileException | Json.ParseException e) {
			err.println("cache-diff: " + e.getMessage());
			return FAILED;
		} catch (IOException | IllegalStateException e) {
			err.println("cache-diff: " + e);
			return FAILED;
		}
	}
}
