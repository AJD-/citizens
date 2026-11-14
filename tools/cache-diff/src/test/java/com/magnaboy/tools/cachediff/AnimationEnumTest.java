package com.magnaboy.tools.cachediff;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class AnimationEnumTest {
	private static final String SOURCE = "package com.magnaboy;\n"
		+ "\n"
		+ "import lombok.Getter;\n"
		+ "\n"
		+ "@Getter\n"
		+ "public enum AnimationID {\n"
		+ "\tFlex(8917),\n"
		+ "\tThink(857),\n"
		+ "\n"
		+ "\t// Actions\n"
		+ "\tGrabbing(551),\n"
		+ "\t// Old(123),\n"
		+ "\t/* Gone(456), */\n"
		+ "\tBeeIdle(0),\n"
		+ "\tDwarfMining2(4021),\n"
		+ "\tDwarfSmith(4021),\n"
		+ "\tSwinging(3475);\n"
		+ "\n"
		+ "\tprivate final Integer id;\n"
		+ "\n"
		+ "\tAnimationID(Integer id) {\n"
		+ "\t\tthis.id = id;\n"
		+ "\t}\n"
		+ "\n"
		+ "}\n";

	@Test
	public void readsConstantsInOrderAndSkipsCommentsAndConstructor() {
		List<AnimationEnum.Constant> c = AnimationEnum.parse(SOURCE.replace("\n", "\r\n"));
		assertEquals(7, c.size());
		assertEquals("Flex", c.get(0).name);
		assertEquals(8917, c.get(0).id);
		assertEquals("Grabbing", c.get(2).name);
		assertEquals("BeeIdle", c.get(3).name);
		assertEquals(0, c.get(3).id);
		assertEquals(4021, c.get(5).id);
		assertEquals("Swinging", c.get(6).name);
		assertEquals(3475, c.get(6).id);
	}
}
