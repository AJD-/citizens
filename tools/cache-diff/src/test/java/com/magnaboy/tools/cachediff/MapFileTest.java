package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MapFileTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void formatAndReadRoundTrip() throws Exception {
		Path file = tmp.newFile("map.json").toPath();
		Files.write(file, MapFile.format(ModelIdRewriterTest.chainMap()).getBytes(StandardCharsets.UTF_8));
		Map<Integer, Integer> read = MapFile.read(file);
		assertEquals(ModelIdRewriterTest.chainMap(), read);
	}

	@Test(expected = MapFile.MapFileException.class)
	public void rejectsNonObject() throws Exception {
		Path file = tmp.newFile("map.json").toPath();
		Files.write(file, "[1, 2]".getBytes(StandardCharsets.UTF_8));
		MapFile.read(file);
	}

	@Test
	public void evidenceFileSitsNextToTheMap() {
		assertEquals(Paths.get("out", "map.evidence.json"), MapFile.evidencePath(Paths.get("out", "map.json")));
		assertEquals(Paths.get("out", "ids.evidence.json"), MapFile.evidencePath(Paths.get("out", "ids")));
	}
}
