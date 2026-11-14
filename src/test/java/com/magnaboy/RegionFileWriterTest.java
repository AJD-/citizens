package com.magnaboy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Loads every bundled region the way the editor does (through the registry), saves it with {@link RegionFileWriter}
 * into a temporary folder and compares the result with the original file as parsed JSON.
 * <p>
 * Two things do not round-trip, and the comparison allows for exactly these: a citizen entry without {@code remarks}
 * is saved with {@code "remarks": []} (the field's default), and a property the region classes do not have (today only
 * {@code baseNpcId} in 12597.json) is not saved.
 */
public class RegionFileWriterTest {
	private static final Path REGION_DIR = Paths.get("src", "main", "resources", "RegionData");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private CitizenRegistry registry;

	@Before
	public void setUp() {
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		CitizensPlugin plugin = new CitizensPlugin();
		plugin.client = client;
		registry = new CitizenRegistry(plugin, RuneLiteAPI.GSON);
	}

	private static List<Path> regionFiles() throws IOException {
		try (Stream<Path> stream = Files.list(REGION_DIR)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	private static JsonElement parse(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return RuneLiteAPI.GSON.fromJson(reader, JsonElement.class);
		}
	}

	/**
	 * The JSON property names Gson writes for a class: its non-static, non-transient fields, inherited ones included.
	 */
	private static Set<String> serializedNames(Class<?> type) {
		Set<String> names = new HashSet<>();
		for (Class<?> c = type; c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				int modifiers = field.getModifiers();
				if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
					names.add(field.getName());
				}
			}
		}
		return names;
	}

	/**
	 * What saving the region is expected to produce from the original file.
	 */
	private static JsonObject expectedAfterSave(JsonObject original) {
		JsonObject expected = original.deepCopy();
		keepOnly(expected, serializedNames(CitizenRegion.class));
		for (JsonElement entry : expected.getAsJsonArray("citizenRoster")) {
			JsonObject citizen = entry.getAsJsonObject();
			keepOnly(citizen, serializedNames(CitizenInfo.class));
			if (!citizen.has("remarks")) {
				citizen.add("remarks", new JsonArray());
			}
		}
		for (JsonElement entry : expected.getAsJsonArray("sceneryRoster")) {
			keepOnly(entry.getAsJsonObject(), serializedNames(SceneryInfo.class));
		}
		return expected;
	}

	private static void keepOnly(JsonObject object, Set<String> names) {
		object.keySet().removeIf(name -> !names.contains(name));
	}

	@Test
	public void everyBundledRegionRoundTrips() throws IOException {
		List<Path> files = regionFiles();
		Assert.assertFalse("No region files found in " + REGION_DIR.toAbsolutePath(), files.isEmpty());
		Path outputDir = folder.getRoot().toPath();
		RegionFileWriter writer = new RegionFileWriter(RuneLiteAPI.GSON, outputDir);

		List<String> failures = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString();
			int regionId = Integer.parseInt(name.substring(0, name.length() - ".json".length()));
			CitizenRegion region = registry.loadRegion(regionId);
			Assert.assertNotNull(name + " did not load", region);

			Path written = writer.write(region);

			Assert.assertEquals(outputDir.resolve(name), written);
			JsonObject original = parse(file).getAsJsonObject();
			JsonObject saved = parse(written).getAsJsonObject();
			if (saved.getAsJsonArray("citizenRoster").size() != original.getAsJsonArray("citizenRoster").size()
				|| saved.getAsJsonArray("sceneryRoster").size() != original.getAsJsonArray("sceneryRoster").size()) {
				failures.add(name + ": roster sizes changed");
			} else if (!expectedAfterSave(original).equals(saved)) {
				failures.add(name + ": saved content differs");
			}
		}

		if (!failures.isEmpty()) {
			Assert.fail(failures.size() + " region(s) did not round-trip\n  " + String.join("\n  ", failures));
		}
	}

	@Test
	public void writesIntoTheGivenDirectoryOnly() throws IOException {
		CitizenRegion region = new CitizenRegion();
		region.regionId = 1234;
		region.version = 0.8f;
		Path outputDir = folder.newFolder("out").toPath();

		Path written = new RegionFileWriter(RuneLiteAPI.GSON, outputDir).write(region);

		Assert.assertEquals(outputDir.resolve("1234.json"), written);
		try (Stream<Path> stream = Files.list(outputDir)) {
			Assert.assertEquals(1, stream.count());
		}
		JsonObject saved = parse(written).getAsJsonObject();
		Assert.assertEquals(1234, saved.get("regionId").getAsInt());
		Assert.assertEquals(0, saved.getAsJsonArray("citizenRoster").size());
		Assert.assertEquals(0, saved.getAsJsonArray("sceneryRoster").size());
	}

	@Test
	public void defaultDirectoryIsTheSourceTreesRegionData() {
		Assert.assertEquals(REGION_DIR.toAbsolutePath(), RegionFileWriter.REGION_DATA_DIRECTORY);
	}
}
