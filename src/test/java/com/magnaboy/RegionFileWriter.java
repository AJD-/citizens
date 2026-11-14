package com.magnaboy;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Inject;

/**
 * Writes region files for the contributor editor. It is the only class that writes files, and it lives in
 * {@code src/test} so that nothing the Plugin Hub builds touches the file system.
 */
class RegionFileWriter {
	/**
	 * The region data in the source tree, resolved against the working directory (the project root under
	 * {@code ./gradlew run}).
	 */
	static final Path REGION_DATA_DIRECTORY = Paths.get("src", "main", "resources", "RegionData").toAbsolutePath();

	private final Gson gson;
	private final Path directory;

	@Inject
	RegionFileWriter(Gson gson) {
		this(gson, REGION_DATA_DIRECTORY);
	}

	RegionFileWriter(Gson gson, Path directory) {
		this.gson = gson.newBuilder().setPrettyPrinting().create();
		this.directory = directory;
	}

	/**
	 * Writes {@code region} to {@code <regionId>.json} in the directory, pretty-printed, replacing any existing file.
	 * For a region the registry holds, call it on the client thread, which owns the rosters.
	 *
	 * @return the file written
	 */
	Path write(CitizenRegion region) throws IOException {
		Path file = directory.resolve(region.regionId + ".json");
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(region, writer);
		} catch (JsonIOException e) {
			throw new IOException("Could not write " + file, e);
		}
		return file;
	}
}
