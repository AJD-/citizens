package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the plugin's {@code RegionData/*.json} files.
 *
 * <p>Files are decoded as ISO-8859-1 so that every byte maps to exactly one char: offsets in the
 * parsed tree are byte offsets, and writing the text back with the same charset reproduces the file
 * byte for byte (UTF-8 multi-byte sequences in names pass through untouched).
 */
final class RegionData {
	static final String CITIZEN_ROSTER = "citizenRoster";
	static final String SCENERY_ROSTER = "sceneryRoster";
	static final String SCENERY_TYPE = "Scenery";

	private RegionData() {
	}

	/** One entity of a region file. */
	static final class Entity {
		final String file;
		final boolean citizen;
		final String name;
		final String uuid;
		final Json.Arr modelIds;
		final int[] models;
		final int[] recolorFind;
		final int[] mergedObjectIds;

		Entity(String file, boolean citizen, Json.Obj obj) {
			this.file = file;
			this.citizen = citizen;
			this.name = display(obj.string("name"));
			this.uuid = obj.string("uuid");
			Json.Value ids = obj.get("modelIds");
			this.modelIds = ids instanceof Json.Arr ? (Json.Arr) ids : null;
			this.models = Json.ints(obj.array("modelIds"));
			this.recolorFind = Json.ints(obj.array("modelRecolorFind"));
			List<Integer> merged = new ArrayList<>();
			for (Json.Value m : obj.array("mergedObjects")) {
				if (m instanceof Json.Obj) {
					Json.Value id = ((Json.Obj) m).get("objectID");
					if (id instanceof Json.Num && ((Json.Num) id).isInt()) {
						merged.add(((Json.Num) id).intValue());
					}
				}
			}
			this.mergedObjectIds = merged.stream().mapToInt(Integer::intValue).toArray();
		}

		String label() {
			String who = name != null ? name : (citizen ? "citizen" : "scenery");
			return who + " (" + file + (uuid != null ? " " + uuid : "") + ")";
		}
	}

	/**
	 * All entities of a parsed region file. An entity counts as a citizen when it sits in
	 * {@code citizenRoster} and its {@code entityType} is not {@code Scenery}; everything else
	 * (the whole {@code sceneryRoster}, and any Scenery-typed entry) is scenery.
	 */
	static List<Entity> entities(String fileName, Json.Value root) {
		List<Entity> out = new ArrayList<>();
		if (!(root instanceof Json.Obj)) {
			return out;
		}
		Json.Obj file = (Json.Obj) root;
		for (Json.Value v : file.array(CITIZEN_ROSTER)) {
			if (v instanceof Json.Obj) {
				Json.Obj o = (Json.Obj) v;
				out.add(new Entity(fileName, !SCENERY_TYPE.equals(o.string("entityType")), o));
			}
		}
		for (Json.Value v : file.array(SCENERY_ROSTER)) {
			if (v instanceof Json.Obj) {
				out.add(new Entity(fileName, false, (Json.Obj) v));
			}
		}
		return out;
	}

	/** The {@code *.json} files of a directory, sorted by name. */
	static List<Path> files(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
	}

	static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}

	/** Re-decodes an ISO-8859-1 string read by {@link #read} as UTF-8 for display. */
	static String display(String latin1) {
		return latin1 == null ? null : new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
	}

	/** Every entity of every file in the directory. */
	static List<Entity> load(Path dir) throws IOException, Json.ParseException {
		List<Entity> out = new ArrayList<>();
		for (Path f : files(dir)) {
			String name = f.getFileName().toString();
			try {
				out.addAll(entities(name, Json.parse(read(f))));
			} catch (Json.ParseException e) {
				throw new Json.ParseException(name + ": " + e.getMessage());
			}
		}
		return out;
	}
}
