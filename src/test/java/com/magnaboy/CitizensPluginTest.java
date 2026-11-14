package com.magnaboy;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CitizensPluginTest {
	@SuppressWarnings("unchecked") // generic varargs of loadBuiltin
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(CitizensPlugin.class, CitizensDevPlugin.class);
		RuneLite.main(args);
	}
}
