package com.magnaboy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.magnaboy.scripting.ActionType;
import com.magnaboy.scripting.ScriptAction;
import com.magnaboy.scripting.ScriptFile;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.coords.WorldPoint;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Loads every bundled region and script file and checks the invariants that the plugin silently relies on, so that
 * broken data fails the build instead of failing quietly in game.
 */
public class DataIntegrityTest {
	private static final float SUPPORTED_VERSION = 0.8f;
	private static final Path REGION_DIR = Paths.get("src", "main", "resources", "RegionData");
	private static final Path SCRIPT_DIR = Paths.get("src", "main", "resources", "Scripts");
	private static final String NPC_MODELS_RESOURCE = "/npcs-models.json";

	private static final Set<EntityType> CITIZEN_TYPES = new HashSet<>(Arrays.asList(
		EntityType.StationaryCitizen, EntityType.WanderingCitizen, EntityType.ScriptedCitizen));

	/** Serialisation shape of a region file; deliberately independent of CitizenRegion. */
	private static class RegionDto {
		float version;
		int regionId;
		List<CitizenInfo> citizenRoster;
		List<SceneryInfo> sceneryRoster;
	}

	private static class RegionFile {
		String fileName;
		RegionDto dto;
		JsonObject raw;

		List<JsonObject> rawCitizens() {
			return rawEntries("citizenRoster");
		}

		List<JsonObject> rawScenery() {
			return rawEntries("sceneryRoster");
		}

		private List<JsonObject> rawEntries(String key) {
			List<JsonObject> entries = new ArrayList<>();
			JsonElement array = raw.get(key);
			if (array != null && array.isJsonArray()) {
				for (JsonElement e : array.getAsJsonArray()) {
					entries.add(e.getAsJsonObject());
				}
			}
			return entries;
		}
	}

	private static class ScriptData {
		String name;
		ScriptFile file;
		JsonObject raw;
	}

	private static final Map<String, RegionFile> regions = new TreeMap<>();
	private static final Map<String, ScriptData> scripts = new TreeMap<>();

	@BeforeClass
	public static void loadData() throws IOException {
		for (Path path : listJsonFiles(REGION_DIR)) {
			RegionFile region = new RegionFile();
			region.fileName = path.getFileName().toString();
			region.dto = parse(path, RegionDto.class);
			region.raw = parse(path, JsonObject.class);
			regions.put(region.fileName, region);
		}
		for (Path path : listJsonFiles(SCRIPT_DIR)) {
			ScriptData script = new ScriptData();
			String fileName = path.getFileName().toString();
			script.name = fileName.substring(0, fileName.length() - ".json".length());
			script.file = parse(path, ScriptFile.class);
			script.raw = parse(path, JsonObject.class);
			scripts.put(script.name, script);
		}
	}

	private static List<Path> listJsonFiles(Path dir) throws IOException {
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".json"))
				.sorted()
				.collect(Collectors.toList());
		}
	}

	private static <T> T parse(Path path, Class<T> type) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return RuneLiteAPI.GSON.fromJson(reader, type);
		}
	}

	private static void assertNoFailures(String what, List<String> failures) {
		if (!failures.isEmpty()) {
			Assert.fail(what + ": " + failures.size() + " violation(s)\n  " + String.join("\n  ", failures));
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------------------

	private static String label(String fileName, EntityInfo info, JsonObject raw) {
		String name = null;
		if (info instanceof CitizenInfo) {
			name = ((CitizenInfo) info).name;
		}
		String uuid = raw.has("uuid") ? raw.get("uuid").getAsString() : String.valueOf(info.uuid);
		return fileName + " [" + (name == null ? "" : name + " ") + uuid + "]";
	}

	private static boolean has(JsonObject raw, String key) {
		return raw.has(key) && !raw.get(key).isJsonNull();
	}

	private static String rawString(JsonObject raw, String key) {
		return has(raw, key) ? raw.get(key).getAsString() : null;
	}

	private static <E extends Enum<E>> boolean resolves(Class<E> type, String value) {
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(value)) {
				return true;
			}
		}
		return false;
	}

	/** Checks a raw enum-typed property; returns an error message or null. Absent properties are fine. */
	private static <E extends Enum<E>> String badEnum(JsonObject raw, String key, Class<E> type) {
		String value = rawString(raw, key);
		if (value != null && !resolves(type, value)) {
			return key + " '" + value + "' is not a " + type.getSimpleName() + " constant";
		}
		return null;
	}

	private static boolean isEmpty(String s) {
		return s == null || s.trim().isEmpty();
	}

	private static List<List<String>> allRegionFailures(RegionCheck check) {
		List<List<String>> out = new ArrayList<>();
		for (RegionFile region : regions.values()) {
			List<String> failures = new ArrayList<>();
			check.run(region, failures);
			out.add(failures);
		}
		return out;
	}

	private interface RegionCheck {
		void run(RegionFile region, List<String> failures);
	}

	private static void checkAllRegions(String what, RegionCheck check) {
		List<String> all = new ArrayList<>();
		for (List<String> failures : allRegionFailures(check)) {
			all.addAll(failures);
		}
		assertNoFailures(what, all);
	}

	private static List<CitizenInfo> citizens(RegionFile region) {
		return region.dto.citizenRoster == null ? new ArrayList<>() : region.dto.citizenRoster;
	}

	private static List<SceneryInfo> scenery(RegionFile region) {
		return region.dto.sceneryRoster == null ? new ArrayList<>() : region.dto.sceneryRoster;
	}

	private static int fileRegionId(RegionFile region) {
		return Integer.parseInt(region.fileName.substring(0, region.fileName.length() - ".json".length()));
	}

	// ---------------------------------------------------------------------------------------------------------
	// Region files
	// ---------------------------------------------------------------------------------------------------------

	@Test
	public void dataFilesAreFound() {
		Assert.assertFalse("No region files found in " + REGION_DIR.toAbsolutePath(), regions.isEmpty());
		Assert.assertFalse("No script files found in " + SCRIPT_DIR.toAbsolutePath(), scripts.isEmpty());
	}

	// Invariants 1, 2 (file level)
	@Test
	public void regionFileStructure() {
		checkAllRegions("Region file structure", (region, failures) -> {
			if (region.dto.version != SUPPORTED_VERSION) {
				failures.add(region.fileName + ": version " + region.dto.version + " != " + SUPPORTED_VERSION);
			}
			if (!region.fileName.matches("\\d+\\.json")) {
				failures.add(region.fileName + ": file name is not <regionId>.json");
				return;
			}
			if (region.dto.regionId != fileRegionId(region)) {
				failures.add(region.fileName + ": regionId " + region.dto.regionId + " does not match file name");
			}
			if (region.dto.citizenRoster == null) {
				failures.add(region.fileName + ": missing citizenRoster");
			}
			if (region.dto.sceneryRoster == null) {
				failures.add(region.fileName + ": missing sceneryRoster");
			}
		});
	}

	// Invariants 2 (entries), 3, 4, 5
	@Test
	public void idsAndLocations() {
		Map<UUID, String> seen = new HashMap<>();
		List<String> failures = new ArrayList<>();
		for (RegionFile region : regions.values()) {
			int regionId = fileRegionId(region);
			List<EntityInfo> infos = new ArrayList<>();
			List<JsonObject> raws = new ArrayList<>();
			infos.addAll(citizens(region));
			raws.addAll(region.rawCitizens());
			infos.addAll(scenery(region));
			raws.addAll(region.rawScenery());

			for (int i = 0; i < infos.size(); i++) {
				EntityInfo info = infos.get(i);
				String label = label(region.fileName, info, raws.get(i));
				if (info.regionId != regionId) {
					failures.add(label + ": entry regionId " + info.regionId + " != file regionId " + regionId);
				}
				if (info.uuid == null) {
					failures.add(label + ": missing uuid");
				} else {
					String previous = seen.put(info.uuid, label);
					if (previous != null) {
						failures.add(label + ": duplicate uuid, also used by " + previous);
					}
				}
				if (info.worldLocation == null) {
					failures.add(label + ": missing worldLocation");
					continue;
				}
				boolean wanderer = info.entityType == EntityType.WanderingCitizen;
				if (!wanderer) {
					if (info.worldLocation.getRegionID() != regionId) {
						failures.add(label + ": worldLocation " + info.worldLocation + " is in region "
							+ info.worldLocation.getRegionID() + ", expected " + regionId);
					}
				}
			}

			for (int i = 0; i < citizens(region).size(); i++) {
				CitizenInfo info = citizens(region).get(i);
				if (info.entityType != EntityType.WanderingCitizen) {
					continue;
				}
				String label = label(region.fileName, info, region.rawCitizens().get(i));
				WorldPoint bl = info.wanderBoxBL;
				WorldPoint tr = info.wanderBoxTR;
				if (bl == null || tr == null) {
					failures.add(label + ": wanderer needs both wanderBoxBL and wanderBoxTR");
					continue;
				}
				if (bl.getPlane() != tr.getPlane()) {
					failures.add(label + ": wander box corners are on different planes");
				}
				if (bl.getRegionID() != regionId && tr.getRegionID() != regionId) {
					failures.add(label + ": neither wander box corner is in region " + regionId);
				}
			}
		}
		assertNoFailures("Ids and locations", failures);
	}

	/** Checks shared by citizens and scenery: invariants 7 (animations), 8, 9, 10, 11, 15. */
	private static void checkCommonEntity(String label, EntityInfo info, JsonObject raw, List<String> failures) {
		String error = badEnum(raw, "idleAnimation", AnimationID.class);
		if (error != null) {
			failures.add(label + ": " + error);
		}
		if (info.modelIds == null || info.modelIds.length == 0) {
			failures.add(label + ": modelIds is empty");
		}
		int findLength = info.modelRecolorFind == null ? 0 : info.modelRecolorFind.length;
		int replaceLength = info.modelRecolorReplace == null ? 0 : info.modelRecolorReplace.length;
		if (findLength != replaceLength) {
			failures.add(label + ": modelRecolorFind has " + findLength + " entries but modelRecolorReplace has "
				+ replaceLength);
		}
		if (info.scale != null && info.scale.length != 3) {
			failures.add(label + ": scale has " + info.scale.length + " elements, expected 3");
		}
		if (info.translate != null && info.translate.length != 3) {
			failures.add(label + ": translate has " + info.translate.length + " elements, expected 3");
		}
		if (info.baseOrientation != null && (info.baseOrientation < 0 || info.baseOrientation > 2047)) {
			failures.add(label + ": baseOrientation " + info.baseOrientation + " is outside 0..2047");
		}
		if (info.mergedObjects != null) {
			for (int i = 0; i < info.mergedObjects.size(); i++) {
				MergedObject merged = info.mergedObjects.get(i);
				if (merged == null) {
					failures.add(label + ": mergedObjects[" + i + "] is null");
					continue;
				}
				if (merged.objectID <= 0) {
					failures.add(label + ": mergedObjects[" + i + "].objectID " + merged.objectID + " must be > 0");
				}
				if (merged.count90CCWRotations < 0) {
					failures.add(label + ": mergedObjects[" + i + "].count90CCWRotations "
						+ merged.count90CCWRotations + " must be >= 0");
				}
			}
		}
	}

	// Invariants 6 (citizens), 7, 8-13, 14 (shape), 15
	@Test
	public void citizenEntries() {
		checkAllRegions("Citizens", (region, failures) -> {
			List<CitizenInfo> infos = citizens(region);
			List<JsonObject> raws = region.rawCitizens();
			for (int i = 0; i < infos.size(); i++) {
				CitizenInfo info = infos.get(i);
				JsonObject raw = raws.get(i);
				String label = label(region.fileName, info, raw);

				// The citizen loader switches on entityType, so a missing or misspelled value would throw.
				String type = rawString(raw, "entityType");
				if (type == null) {
					failures.add(label + ": citizen is missing entityType");
				} else if (!resolves(EntityType.class, type) || !CITIZEN_TYPES.contains(info.entityType)) {
					failures.add(label + ": entityType '" + type + "' is not one of " + CITIZEN_TYPES);
				}

				checkCommonEntity(label, info, raw, failures);

				if (!has(raw, "idleAnimation")) {
					failures.add(label + ": citizen has no idleAnimation");
				}
				String moveError = badEnum(raw, "moveAnimation", AnimationID.class);
				if (moveError != null) {
					failures.add(label + ": " + moveError);
				}
				if (isEmpty(info.name)) {
					failures.add(label + ": name is empty");
				}
				if (isEmpty(info.examineText)) {
					failures.add(label + ": examineText is empty");
				}
				JsonElement remarks = raw.get("remarks");
				if (remarks != null && remarks.isJsonArray()) {
					JsonArray array = remarks.getAsJsonArray();
					for (int r = 0; r < array.size(); r++) {
						JsonElement remark = array.get(r);
						if (remark.isJsonNull() || remark.getAsString().isEmpty()) {
							failures.add(label + ": remarks[" + r + "] is null or empty");
						}
					}
				}

				if (info.entityType == EntityType.WanderingCitizen && info.wanderBoxBL != null
					&& info.wanderBoxTR != null) {
					try {
						Util.calculateBoundingBox(info.wanderBoxBL, info.wanderBoxTR);
					} catch (RuntimeException e) {
						failures.add(label + ": invalid wander box: " + e.getMessage());
					}
				}

				if (info.entityType == EntityType.ScriptedCitizen) {
					if (isEmpty(info.startScript)) {
						failures.add(label + ": ScriptedCitizen has no startScript");
					}
				} else if (has(raw, "startScript")) {
					failures.add(label + ": " + info.entityType + " must not have a startScript");
				}
			}
		});
	}

	// Invariants 6 (scenery), 7, 8-11, 15
	@Test
	public void sceneryEntries() {
		checkAllRegions("Scenery", (region, failures) -> {
			List<SceneryInfo> infos = scenery(region);
			List<JsonObject> raws = region.rawScenery();
			for (int i = 0; i < infos.size(); i++) {
				SceneryInfo info = infos.get(i);
				JsonObject raw = raws.get(i);
				String label = label(region.fileName, info, raw);

				// CitizenRegion.loadScenery never reads entityType, so an absent value is tolerated. When present it
				// must still be a valid constant and must be Scenery.
				String type = rawString(raw, "entityType");
				if (type != null && (!resolves(EntityType.class, type) || info.entityType != EntityType.Scenery)) {
					failures.add(label + ": entityType '" + type + "' must be Scenery (or absent)");
				}

				checkCommonEntity(label, info, raw, failures);
			}
		});
	}

	// The plugin only adds to the scene: it no longer hides real game objects, so the old key must not come back
	@Test
	public void noEntryHidesAGameObject() {
		checkAllRegions("Hidden game objects", (region, failures) -> {
			List<JsonObject> raws = new ArrayList<>(region.rawCitizens());
			raws.addAll(region.rawScenery());
			for (JsonObject raw : raws) {
				if (raw.has("removedObject")) {
					String uuid = raw.has("uuid") ? raw.get("uuid").getAsString() : "?";
					failures.add(region.fileName + " [" + uuid + "]: has a removedObject key");
				}
			}
		});
	}

	// Invariant 16 (scope: citizens' modelIds only, as scripts/validate.js did)
	@Test
	public void citizenModelIdsAreValidNpcModels() throws IOException {
		Set<Integer> valid;
		try (Reader reader = new InputStreamReader(
			DataIntegrityTest.class.getResourceAsStream(NPC_MODELS_RESOURCE), StandardCharsets.UTF_8)) {
			Type listType = new TypeToken<List<Integer>>() { }.getType();
			List<Integer> ids = RuneLiteAPI.GSON.fromJson(reader, listType);
			valid = new HashSet<>(ids);
		}
		Assert.assertFalse("npcs-models.json is empty", valid.isEmpty());

		final Set<Integer> validIds = valid;
		checkAllRegions("NPC model ids", (region, failures) -> {
			List<CitizenInfo> infos = citizens(region);
			for (int i = 0; i < infos.size(); i++) {
				CitizenInfo info = infos.get(i);
				if (info.modelIds == null) {
					continue;
				}
				for (int id : info.modelIds) {
					if (!validIds.contains(id)) {
						failures.add(label(region.fileName, info, region.rawCitizens().get(i))
							+ ": modelId " + id + " is not in npcs-models.json");
					}
				}
			}
		});
	}

	// ---------------------------------------------------------------------------------------------------------
	// Script files
	// ---------------------------------------------------------------------------------------------------------

	// Invariants 17-22
	@Test
	public void scriptFiles() {
		List<String> failures = new ArrayList<>();
		for (ScriptData script : scripts.values()) {
			String file = script.name + ".json";
			JsonElement rawActions = script.raw.get("actions");
			if (rawActions == null || !rawActions.isJsonArray() || rawActions.getAsJsonArray().size() == 0) {
				failures.add(file + ": action list is missing or empty");
				continue;
			}
			JsonArray raws = rawActions.getAsJsonArray();
			List<ScriptAction> actions = new ArrayList<>();
			if (script.file.actions != null) {
				for (ScriptAction action : script.file.actions) {
					actions.add(action);
				}
			}
			if (actions.size() != raws.size()) {
				failures.add(file + ": parsed " + actions.size() + " actions but file has " + raws.size());
				continue;
			}

			for (int i = 0; i < actions.size(); i++) {
				ScriptAction action = actions.get(i);
				JsonObject raw = raws.get(i).getAsJsonObject();
				String at = file + " action #" + i;

				String typeName = rawString(raw, "action");
				if (typeName == null || !resolves(ActionType.class, typeName)) {
					failures.add(at + ": action '" + typeName + "' is not an ActionType constant");
					continue;
				}
				at += " (" + typeName + ")";

				if (action.secondsTilNextAction < 0) {
					failures.add(at + ": secondsTilNextAction " + action.secondsTilNextAction + " is negative");
				}

				switch (ActionType.valueOf(typeName)) {
					case WalkTo:
						if (action.targetPosition == null) {
							failures.add(at + ": missing targetPosition");
						}
						break;
					case FaceDirection:
						if (!has(raw, "targetRotation")) {
							failures.add(at + ": missing targetRotation");
						} else {
							String error = badEnum(raw, "targetRotation", CardinalDirection.class);
							if (error != null) {
								failures.add(at + ": " + error);
							}
						}
						break;
					case Animation:
						if (!has(raw, "animationId")) {
							failures.add(at + ": missing animationId");
						} else {
							String error = badEnum(raw, "animationId", AnimationID.class);
							if (error != null) {
								failures.add(at + ": " + error);
							}
						}
						if (action.timesToLoop != null && action.timesToLoop < 1) {
							failures.add(at + ": timesToLoop " + action.timesToLoop + " must be >= 1");
						}
						break;
					case Say:
						if (isEmpty(action.message)) {
							failures.add(at + ": message is empty");
						}
						break;
					default:
						break;
				}
			}
		}
		assertNoFailures("Script files", failures);
	}

	// Invariants 14 (script exists), 23
	@Test
	public void scriptedCitizensUseValidScripts() {
		checkAllRegions("Scripted citizens", (region, failures) -> {
			List<CitizenInfo> infos = citizens(region);
			for (int i = 0; i < infos.size(); i++) {
				CitizenInfo info = infos.get(i);
				if (info.entityType != EntityType.ScriptedCitizen || isEmpty(info.startScript)) {
					continue;
				}
				String label = label(region.fileName, info, region.rawCitizens().get(i));
				ScriptData script = scripts.get(info.startScript);
				if (script == null) {
					failures.add(label + ": startScript '" + info.startScript + "' has no Scripts/"
						+ info.startScript + ".json");
					continue;
				}
				if (info.worldLocation == null || script.file.actions == null) {
					continue;
				}
				for (ScriptAction action : script.file.actions) {
					if (action.action == ActionType.WalkTo && action.targetPosition != null
						&& action.targetPosition.getPlane() != info.worldLocation.getPlane()) {
						failures.add(label + ": script '" + info.startScript + "' walks to plane "
							+ action.targetPosition.getPlane() + " but citizen is on plane "
							+ info.worldLocation.getPlane());
					}
				}
			}
		});
	}
}
