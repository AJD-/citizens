package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import org.junit.Test;

public class JsonTest {
	@Test
	public void recordsTheSourceOffsetsOfValues() throws Exception {
		String text = "\uFEFF{ \"a\" : [ 12, -3 ,4.5 ], \"b\": \"x\\\"y\" }";
		Json.Obj root = (Json.Obj) Json.parse(text);
		assertEquals(1, root.start);
		assertEquals(text.length(), root.end);
		List<Json.Value> a = root.array("a");
		Json.Num twelve = (Json.Num) a.get(0);
		assertEquals("12", text.substring(twelve.start, twelve.end));
		assertEquals(12, twelve.intValue());
		Json.Num minusThree = (Json.Num) a.get(1);
		assertEquals("-3", text.substring(minusThree.start, minusThree.end));
		assertFalse(((Json.Num) a.get(2)).isInt());
		assertEquals("x\"y", root.string("b"));
	}

	@Test
	public void integersOutsideIntRangeAreNotInts() throws Exception {
		Json.Arr arr = (Json.Arr) Json.parse("[2147483647, 2147483648, -2147483648, -2147483649]");
		assertTrue(((Json.Num) arr.items.get(0)).isInt());
		assertFalse(((Json.Num) arr.items.get(1)).isInt());
		assertTrue(((Json.Num) arr.items.get(2)).isInt());
		assertFalse(((Json.Num) arr.items.get(3)).isInt());
		assertEquals(0, Json.ints(arr.items).length);
	}

	@Test
	public void rejectsDuplicateKeys() {
		assertParseError("{\"modelIds\": [1], \"modelIds\": [2]}", "duplicate key \"modelIds\"");
	}

	@Test
	public void rejectsMalformedNumbers() {
		assertParseError("[--1]", "malformed number \"--1\"");
		assertParseError("[1-2]", "malformed number \"1-2\"");
		assertParseError("[01]", "malformed number \"01\"");
		assertParseError("[1.]", "malformed number \"1.\"");
	}

	@Test
	public void reportsTheLineOfAnError() {
		assertParseError("{\n\"a\": 1,\n\"a\": 2}", "at line 3");
	}

	private static void assertParseError(String text, String expected) {
		try {
			Json.parse(text);
			fail("expected a parse error for " + text);
		} catch (Json.ParseException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(expected));
		}
	}
}
