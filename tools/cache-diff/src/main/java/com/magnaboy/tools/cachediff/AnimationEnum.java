package com.magnaboy.tools.cachediff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the constants of the plugin's {@code AnimationID} enum from its Java source. */
final class AnimationEnum {
	private static final Pattern CONSTANT = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*(-?\\d+)\\s*\\)\\s*[,;]", Pattern.MULTILINE);

	private AnimationEnum() {
	}

	static final class Constant {
		final String name;
		final int id;

		Constant(String name, int id) {
			this.name = name;
			this.id = id;
		}
	}

	/**
	 * Constants in source order; line and block comments are ignored.
	 *
	 * @throws IllegalArgumentException if a constant's id does not fit in an int
	 */
	static List<Constant> parse(String source) {
		String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
		List<Constant> out = new ArrayList<>();
		Matcher m = CONSTANT.matcher(code);
		while (m.find()) {
			Integer id = Json.toInt(m.group(2));
			if (id == null) {
				throw new IllegalArgumentException("animation constant " + m.group(1) + " has an id out of int range: " + m.group(2));
			}
			out.add(new Constant(m.group(1), id));
		}
		return out;
	}
}
