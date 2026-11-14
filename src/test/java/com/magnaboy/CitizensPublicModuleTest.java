package com.magnaboy;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.util.Providers;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds the injectors the way {@code PluginManager.instantiate} does (client 1.13.6): each plugin gets a child of the
 * RuneLite injector made from its dependencies' public modules plus a module that binds the plugin to its instance
 * and installs it. Checks that the dev plugin is handed the Citizens plugin's own instances.
 */
public class CitizensPublicModuleTest {
	private Injector runelite;

	@Before
	public void setUp() {
		ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getConfig(CitizensConfig.class)).thenReturn(mock(CitizensConfig.class));

		runelite = Guice.createInjector(binder -> {
			// A new mock on every injection, so that injecting a plugin's fields a second time would show
			binder.bind(Client.class).toProvider((Provider<Client>) () -> mock(Client.class));
			binder.bind(ClientThread.class).toProvider(Providers.of(mock(ClientThread.class)));
			binder.bind(Gson.class).toProvider(Providers.of(RuneLiteAPI.GSON));
			binder.bind(ConfigManager.class).toProvider(Providers.of(configManager));
			binder.bind(ChatMessageManager.class).toProvider(Providers.of(mock(ChatMessageManager.class)));
			binder.bind(OverlayManager.class).toProvider(Providers.of(mock(OverlayManager.class)));
			binder.bind(ModelOutlineRenderer.class).toProvider(Providers.of(mock(ModelOutlineRenderer.class)));
			binder.bind(ClientToolbar.class).toProvider(Providers.of(mock(ClientToolbar.class)));
			binder.bind(PluginManager.class).toProvider(Providers.of(mock(PluginManager.class)));
		});
	}

	/**
	 * {@code PluginModuleFactory.PluginModule}: binds the plugin to its instance and installs it.
	 */
	@SuppressWarnings("unchecked")
	private static <P extends Plugin> Module pluginModule(P plugin) {
		return binder -> {
			binder.bind((Class<P>) plugin.getClass()).toInstance(plugin);
			binder.install(plugin);
		};
	}

	@Test
	public void devPluginGetsTheCitizensPluginsInstances() {
		CitizensPlugin plugin = new CitizensPlugin();
		Injector pluginInjector = runelite.createChildInjector(pluginModule(plugin));
		CitizenRegistry registry = pluginInjector.getInstance(CitizenRegistry.class);
		Client clientBefore = plugin.client;
		Assert.assertNotNull(clientBefore);

		CitizensDevPlugin devPlugin = new CitizensDevPlugin();
		Module publicModule = plugin.getPublicModule();
		Assert.assertNotNull("PluginManager refuses a dependency without a public module", publicModule);
		Injector devInjector = runelite.createChildInjector(publicModule, pluginModule(devPlugin));

		Assert.assertSame(registry, devInjector.getInstance(CitizenRegistry.class));
		Assert.assertSame(plugin, devInjector.getInstance(CitizensPlugin.class));
		Assert.assertSame(registry, devPlugin.registry);
		Assert.assertSame(plugin, devPlugin.plugin);
		Assert.assertNotNull(devPlugin.overlay);
		// Handing out the instance must not inject the Citizens plugin's fields again
		Assert.assertSame(clientBefore, plugin.client);
	}
}
