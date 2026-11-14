package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ModelIdRewriterTest {
	/**
	 * CRLF line endings, odd spacing, a non-ASCII name, entityType after modelIds, a Scenery-typed
	 * entry inside citizenRoster, merged objects and recolours that contain mapped numbers.
	 */
	static final String BEFORE = "{\r\n"
		+ "  \"version\": 0.8,\r\n"
		+ "  \"citizenRoster\": [\r\n"
		+ "    {\r\n"
		+ "      \"name\": \"Sjöberg\",\r\n"
		+ "      \"modelIds\"  :[ 1,  2 ,\r\n"
		+ "\t3,7],\r\n"
		+ "      \"modelRecolorFind\": [1, 2],\r\n"
		+ "      \"entityType\": \"StationaryCitizen\",\r\n"
		+ "      \"mergedObjects\": [ { \"objectID\": 1, \"count90CCWRotations\": 2 } ],\r\n"
		+ "      \"worldLocation\": { \"x\": 1, \"y\": 2, \"plane\": 3 }\r\n"
		+ "    },\r\n"
		+ "    { \"entityType\":\"Scenery\", \"modelIds\":[1,2] }\r\n"
		+ "  ],\r\n"
		+ "  \"sceneryRoster\": [ {\"modelIds\": [1, 2, 3], \"entityType\": \"Scenery\"} ]\r\n"
		+ "}\r\n";

	static final String AFTER = "{\r\n"
		+ "  \"version\": 0.8,\r\n"
		+ "  \"citizenRoster\": [\r\n"
		+ "    {\r\n"
		+ "      \"name\": \"Sjöberg\",\r\n"
		+ "      \"modelIds\"  :[ 2,  3 ,\r\n"
		+ "\t100,7],\r\n"
		+ "      \"modelRecolorFind\": [1, 2],\r\n"
		+ "      \"entityType\": \"StationaryCitizen\",\r\n"
		+ "      \"mergedObjects\": [ { \"objectID\": 1, \"count90CCWRotations\": 2 } ],\r\n"
		+ "      \"worldLocation\": { \"x\": 1, \"y\": 2, \"plane\": 3 }\r\n"
		+ "    },\r\n"
		+ "    { \"entityType\":\"Scenery\", \"modelIds\":[1,2] }\r\n"
		+ "  ],\r\n"
		+ "  \"sceneryRoster\": [ {\"modelIds\": [1, 2, 3], \"entityType\": \"Scenery\"} ]\r\n"
		+ "}\r\n";

	/** A chain: 1->2 and 2->3 and 3->100. */
	static Map<Integer, Integer> chainMap() {
		Map<Integer, Integer> map = new HashMap<>();
		map.put(1, 2);
		map.put(2, 3);
		map.put(3, 100);
		return map;
	}

	private static ModelIdRewriter.Result rewrite(String utf8Text, Map<Integer, Integer> map) throws Exception {
		String latin1 = new String(utf8Text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
		return ModelIdRewriter.rewrite("1.json", latin1, map);
	}

	@Test
	public void onlyCitizenModelIdNumbersChangeAndEverythingElseIsByteIdentical() throws Exception {
		ModelIdRewriter.Result r = rewrite(BEFORE, chainMap());
		assertArrayEquals(AFTER.getBytes(StandardCharsets.UTF_8), RegionData.bytes(r.text));
	}

	@Test
	public void chainsAreAppliedOncePerOriginalValue() throws Exception {
		ModelIdRewriter.Result r = rewrite(BEFORE, chainMap());
		assertEquals(3, r.edits.size());
		assertEquals(1, r.edits.get(0).oldId);
		assertEquals(2, r.edits.get(0).newId);
		assertEquals(2, r.edits.get(1).oldId);
		assertEquals(3, r.edits.get(1).newId);
		assertEquals(3, r.edits.get(2).oldId);
		assertEquals(100, r.edits.get(2).newId);
		assertEquals(6, r.edits.get(0).line);
		assertEquals(7, r.edits.get(2).line);
	}

	@Test
	public void sceneryIsUntouched() throws Exception {
		String scenery = "{\"citizenRoster\":[],\"sceneryRoster\":[{\"modelIds\":[1,2,3]}]}";
		ModelIdRewriter.Result r = rewrite(scenery, chainMap());
		assertEquals(scenery, r.text);
		assertTrue(r.edits.isEmpty());
	}

	@Test
	public void noMatchingIdsLeavesTheTextAlone() throws Exception {
		Map<Integer, Integer> map = new HashMap<>();
		map.put(999, 1000);
		ModelIdRewriter.Result r = rewrite(BEFORE, map);
		assertArrayEquals(BEFORE.getBytes(StandardCharsets.UTF_8), RegionData.bytes(r.text));
	}

	@Test
	public void previewShowsEachEditedLineBeforeAndAfter() throws Exception {
		String latin1 = new String(BEFORE.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
		ModelIdRewriter.Result r = ModelIdRewriter.rewrite("1.json", latin1, chainMap());
		List<String> p = ModelIdRewriter.preview("1.json", latin1, r);
		assertTrue(p.get(2).startsWith("@@ Sjöberg (1.json"));
		assertEquals("-    6       \"modelIds\"  :[ 1,  2 ,", p.get(3));
		assertEquals("+    6       \"modelIds\"  :[ 2,  3 ,", p.get(4));
		assertEquals("-    7 \t3,7],", p.get(5));
		assertEquals("+    7 \t100,7],", p.get(6));
	}
}
