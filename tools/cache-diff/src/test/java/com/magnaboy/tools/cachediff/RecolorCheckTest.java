package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RecolorCheckTest {
	private static List<RegionData.Entity> citizen(String modelIds, String recolorFind) throws Exception {
		String json = "{\"citizenRoster\":[{\"name\":\"Bob\",\"modelIds\":[" + modelIds + "],\"modelRecolorFind\":[" + recolorFind + "]}]}";
		return RegionData.entities("1.json", Json.parse(json));
	}

	@Test
	public void reportsColoursLostByTheMappedModelsAndColoursThatNeverMatched() throws Exception {
		FakeCache oldCache = new FakeCache().model(10, "a", 100, 65535);
		FakeCache newCache = new FakeCache().model(10, "b", 300).model(20, "c", 100, 65535);
		// 100 survives on 20 after the map; -1 is 65535 as an unsigned colour; 300 is never matched.
		List<RecolorCheck.Issue> issues = RecolorCheck.check(citizen("10", "100, -1, 400"),
			Collections.singletonMap(10, 20), oldCache, newCache);
		assertEquals(1, issues.size());
		assertEquals(400, issues.get(0).color);
		assertFalse(issues.get(0).lost);

		List<RecolorCheck.Issue> unmapped = RecolorCheck.check(citizen("10", "100"), Collections.emptyMap(), oldCache, newCache);
		assertEquals(1, unmapped.size());
		assertTrue(unmapped.get(0).lost);
	}
}
