package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CacheDiffCliTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final ByteArrayOutputStream err = new ByteArrayOutputStream();

	private int run(String... args) {
		return CacheDiff.run(args, new PrintStream(out, true), new PrintStream(err, true));
	}

	private String err() {
		return new String(err.toByteArray(), StandardCharsets.UTF_8);
	}

	private String out() {
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private void assertOneLineError() {
		assertEquals(1, err().trim().split("\n").length);
		assertFalse(err().contains("\tat "));
	}

	@Test
	public void noArgumentsFails() {
		assertNotEquals(0, run());
		assertOneLineError();
	}

	@Test
	public void unknownCommandFails() {
		assertNotEquals(0, run("frobnicate"));
		assertTrue(err().contains("unknown command"));
		assertOneLineError();
	}

	@Test
	public void missingRequiredOptionFails() {
		assertNotEquals(0, run("models", "--new", "x"));
		assertTrue(err().contains("needs --old"));
		assertOneLineError();
	}

	@Test
	public void unknownOptionFails() {
		assertNotEquals(0, run("anims", "--old", "a", "--new", "b", "--bogus", "c"));
		assertTrue(err().contains("unknown option"));
	}

	@Test
	public void optionWithoutValueFails() {
		assertNotEquals(0, run("apply", "--map"));
		assertTrue(err().contains("--map needs a value"));
	}

	@Test
	public void unreadableCacheFailsWithOneLineAndNoStackTrace() throws Exception {
		Path data = tmp.newFolder("data").toPath();
		Path notACache = tmp.newFolder("empty").toPath();
		assertNotEquals(0, run("models", "--old", notACache.toString(), "--new", notACache.toString(), "--data", data.toString()));
		assertTrue(err().contains("not a game cache"));
		assertOneLineError();
	}

	@Test
	public void missingDataDirectoryFails() throws Exception {
		Path map = tmp.newFile("map.json").toPath();
		Files.write(map, "{}".getBytes(StandardCharsets.UTF_8));
		assertNotEquals(0, run("apply", "--map", map.toString(), "--data", tmp.getRoot().toPath().resolve("nope").toString()));
	}

	@Test
	public void malformedMapFails() throws Exception {
		Path data = tmp.newFolder("data").toPath();
		Path map = tmp.newFile("map.json").toPath();
		Files.write(map, "{ \"12\": \"x\" }".getBytes(StandardCharsets.UTF_8));
		assertNotEquals(0, run("apply", "--map", map.toString(), "--data", data.toString()));
		assertTrue(err().contains("not an integer id"));
		assertOneLineError();
	}

	private Path dataWithOneFile() throws Exception {
		Path data = tmp.newFolder("RegionData").toPath();
		Files.write(data.resolve("1.json"), ModelIdRewriterTest.BEFORE.getBytes(StandardCharsets.UTF_8));
		return data;
	}

	private Path chainMapFile() throws Exception {
		Path map = tmp.newFile("map.json").toPath();
		Files.write(map, MapFile.format(ModelIdRewriterTest.chainMap()).getBytes(StandardCharsets.UTF_8));
		return map;
	}

	@Test
	public void applyDryRunWritesNothing() throws Exception {
		Path data = dataWithOneFile();
		Path map = chainMapFile();
		long modified = Files.getLastModifiedTime(data.resolve("1.json")).toMillis();
		assertEquals(0, run("apply", "--map", map.toString(), "--data", data.toString()));
		assertArrayEquals(ModelIdRewriterTest.BEFORE.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(data.resolve("1.json")));
		assertEquals(modified, Files.getLastModifiedTime(data.resolve("1.json")).toMillis());
		assertTrue(out().contains("+    7 \t100,7],"));
		assertTrue(out().contains("Dry run: nothing written"));
		assertTrue(out().contains("note: the map chains through [2, 3]"));
	}

	@Test
	public void applyWriteRewritesOnlyTheNumbers() throws Exception {
		Path data = dataWithOneFile();
		Path map = chainMapFile();
		assertEquals(0, run("apply", "--map", map.toString(), "--data", data.toString(), "--write"));
		assertArrayEquals(ModelIdRewriterTest.AFTER.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(data.resolve("1.json")));
	}
}
