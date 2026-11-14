package com.magnaboy;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;
import java.util.UUID;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Examine entry the plugin adds to the menu: who gets one and what clicking it does.
 */
public class CitizensPluginMenuTest {
	private static final int REGION_ID = 12850;
	private static final Point MOUSE_INSIDE = new Point(50, 50);

	private CitizensPlugin plugin;
	private CitizenRegion region;
	private Menu menu;
	private MenuEntry entry;
	private ChatMessageManager chatMessageManager;

	@Before
	public void setUp() {
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getMouseCanvasPosition()).thenReturn(MOUSE_INSIDE);
		menu = mock(Menu.class);
		when(client.getMenu()).thenReturn(menu);
		// Every setter returns the entry itself, so that the plugin's chained calls work
		entry = mock(MenuEntry.class, RETURNS_SELF);
		when(menu.createMenuEntry(1)).thenReturn(entry);
		chatMessageManager = mock(ChatMessageManager.class);

		Injector runelite = Guice.createInjector(binder -> {
			binder.bind(Client.class).toProvider(Providers.of(client));
			binder.bind(ClientThread.class).toProvider(Providers.of(mock(ClientThread.class)));
			binder.bind(Gson.class).toProvider(Providers.of(RuneLiteAPI.GSON));
			binder.bind(ChatMessageManager.class).toProvider(Providers.of(chatMessageManager));
			binder.bind(OverlayManager.class).toProvider(Providers.of(mock(OverlayManager.class)));
			binder.bind(CitizensOverlay.class).toProvider(Providers.of(mock(CitizensOverlay.class)));
		});
		plugin = new CitizensPlugin();
		Injector pluginInjector = runelite.createChildInjector(binder -> {
			binder.bind(CitizensPlugin.class).toInstance(plugin);
			binder.install(plugin);
		});
		region = pluginInjector.getInstance(CitizenRegistry.class).createRegion(REGION_ID);
	}

	/**
	 * Registers the entity in the test region under a new uuid.
	 */
	private <E extends Entity<?>> E place(E entity, String name, String examine) {
		entity.setUUID(UUID.randomUUID()).setRegion(REGION_ID);
		entity.name = name;
		entity.examine = examine;
		region.entities.put(entity.uuid, entity);
		return entity;
	}

	private static SimplePolygon boxAround(boolean underMouse) {
		int offset = underMouse ? 0 : 1000;
		return new SimplePolygon(new int[]{offset, offset + 100, offset + 100, offset},
			new int[]{offset, offset, offset + 100, offset + 100}, 4);
	}

	private StationaryCitizen citizen(String name, String examine, boolean spawned, boolean underMouse) {
		return place(new StationaryCitizen(plugin) {
			@Override
			public boolean isActive() {
				return spawned;
			}

			@Override
			public float distanceToPlayer() {
				return 0;
			}

			@Override
			public SimplePolygon getClickbox() {
				return boxAround(underMouse);
			}
		}, name, examine);
	}

	private Scenery scenery(String name, String examine) {
		return place(new Scenery(plugin) {
			@Override
			public boolean isActive() {
				return true;
			}

			@Override
			public float distanceToPlayer() {
				return 0;
			}

			@Override
			public SimplePolygon getClickbox() {
				return boxAround(true);
			}
		}, name, examine);
	}

	@Test
	public void aSpawnedNamedCitizenUnderTheMouseGetsOneExamineEntry() {
		citizen("Alice", "A friendly face.", true, true);

		plugin.onMenuOpened(new MenuOpened());

		verify(menu, times(1)).createMenuEntry(1);
		verify(entry).setOption("Examine");
		verify(entry).setTarget("<col=fffe00>Alice</col>");
		verify(entry).setType(MenuAction.RUNELITE);
		verify(entry).setDeprioritized(true);
	}

	@Test
	public void noEntryForSceneryNamelessUnspawnedOrElsewhere() {
		scenery("A statue", "Cold stone.");
		citizen(null, "Has no name.", true, true);
		citizen("Bob", null, true, true);
		citizen("Carol", "Despawned.", false, true);
		citizen("Dave", "Not under the mouse.", true, false);

		plugin.onMenuOpened(new MenuOpened());

		verify(menu, never()).createMenuEntry(1);
	}

	@Test
	public void clickingExaminesTheEntityTheEntryWasMadeFor() {
		citizen("Alice", "First Alice.", true, true);
		citizen("Alice", "Second Alice.", true, true);

		plugin.onMenuOpened(new MenuOpened());

		ArgumentCaptor<Consumer<MenuEntry>> clicks = captor();
		verify(menu, times(2)).createMenuEntry(1);
		verify(entry, times(2)).onClick(clicks.capture());
		verify(chatMessageManager, never()).queue(any());

		// The entities have no fixed order: each click must post one text, and the two must differ
		ArgumentCaptor<QueuedMessage> messages = ArgumentCaptor.forClass(QueuedMessage.class);
		clicks.getAllValues().get(0).accept(entry);
		verify(chatMessageManager, times(1)).queue(any());
		clicks.getAllValues().get(1).accept(entry);
		verify(chatMessageManager, times(2)).queue(messages.capture());

		String first = messages.getAllValues().get(0).getRuneLiteFormattedMessage();
		String second = messages.getAllValues().get(1).getRuneLiteFormattedMessage();
		Assert.assertTrue(first, first.contains("First Alice.") != first.contains("Second Alice."));
		Assert.assertTrue(second, second.contains("First Alice.") != second.contains("Second Alice."));
		Assert.assertNotEquals(first, second);
	}

	@SuppressWarnings("unchecked") // a captor of a generic type cannot be made without it
	private static ArgumentCaptor<Consumer<MenuEntry>> captor() {
		return ArgumentCaptor.forClass((Class<Consumer<MenuEntry>>) (Class<?>) Consumer.class);
	}
}
