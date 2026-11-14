package com.magnaboy.tools.cachediff;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses {@code <command> --option value ... --flag}. */
final class CliArgs {
	static final class UsageException extends Exception {
		UsageException(String message) {
			super(message);
		}
	}

	final String command;
	private final Map<String, String> values = new HashMap<>();
	private final Set<String> flags = new HashSet<>();

	private CliArgs(String command) {
		this.command = command;
	}

	/**
	 * @param valueOptions options that take a value, e.g. {@code --old}
	 * @param flagOptions options without a value, e.g. {@code --write}
	 */
	static CliArgs parse(String[] args, List<String> valueOptions, List<String> flagOptions) throws UsageException {
		CliArgs a = new CliArgs(args[0]);
		List<String> rest = Arrays.asList(args).subList(1, args.length);
		for (int i = 0; i < rest.size(); i++) {
			String opt = rest.get(i);
			if (flagOptions.contains(opt)) {
				a.flags.add(opt);
			} else if (valueOptions.contains(opt)) {
				if (i + 1 >= rest.size() || rest.get(i + 1).startsWith("--")) {
					throw new UsageException(opt + " needs a value");
				}
				if (a.values.put(opt, rest.get(++i)) != null) {
					throw new UsageException(opt + " given twice");
				}
			} else {
				throw new UsageException("unknown option for " + a.command + ": " + opt);
			}
		}
		return a;
	}

	Path required(String opt) throws UsageException {
		String v = values.get(opt);
		if (v == null) {
			throw new UsageException(command + " needs " + opt);
		}
		return Paths.get(v);
	}

	Path optional(String opt, String fallback) {
		String v = values.get(opt);
		return v == null ? (fallback == null ? null : Paths.get(fallback)) : Paths.get(v);
	}

	boolean flag(String opt) {
		return flags.contains(opt);
	}
}
