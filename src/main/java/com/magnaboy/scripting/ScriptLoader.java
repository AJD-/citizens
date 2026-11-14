package com.magnaboy.scripting;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and caches the bundled script files. Owned by the registry and cleared with it.
 */
@Slf4j
public final class ScriptLoader {
	private final Gson gson;
	private final Map<String, ScriptFile> scriptCache = new HashMap<>();

	public ScriptLoader(Gson gson) {
		this.gson = gson;
	}

	public ScriptFile loadScript(String scriptName) {
		if (scriptName == null || scriptName.isEmpty()) {
			return null;
		}
		if (scriptCache.containsKey(scriptName)) {
			return scriptCache.get(scriptName);
		}

		InputStream inputStream = ScriptLoader.class.getResourceAsStream("/Scripts/" + scriptName + ".json");

		if (inputStream == null) {
			return null;
		}

		try (Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
			ScriptFile script = gson.fromJson(reader, ScriptFile.class);
			scriptCache.put(scriptName, script);
			script.name = scriptName;
			return script;
		} catch (IOException e) {
			log.warn("Script Loading Error: {}", e.getMessage());
			return null;
		}
	}

	public void clear() {
		scriptCache.clear();
	}
}
