package com.magnaboy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.util.Providers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WorldViewLoaded;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the plugin's game state, world view and tick handlers against a mocked client and a bundled region, and
 * checks which objects end up registered with the client.
 */
public class CitizensPluginLifecycleTest {
	/**
	 * A bundled region; the player stands on its first citizen's tile, so that some of its entities are in range.
	 */
	private static final int REGION_ID = 12850;
	private static final int SCENE_SIZE = 104;

	private CitizensPlugin plugin;
	private CitizenRegistry registry;
	private Client client;
	private WorldView topLevel;

	private GameState gameState = GameState.LOGGED_IN;
	private WorldView currentWorldView;
	private Player currentPlayer;
	private boolean instance;
	private int[] mapRegions = {REGION_ID};

	/**
	 * Every object the plugin created, in order. Each one remembers whether it is registered with the client.
	 */
	private final List<RuneLiteObject> objects = new ArrayList<>();
	private final AtomicInteger registrations = new AtomicInteger();

	private final Logger pluginLog = (Logger) LoggerFactory.getLogger("com.magnaboy");
	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	@Before
	public void setUp() {
		// A failure inside an entity is caught and logged by the registry, so a broken mock would only show up here
		logged.start();
		pluginLog.addAppender(logged);

		WorldPoint playerLocation = firstCitizenLocation();

		topLevel = mock(WorldView.class);
		when(topLevel.isTopLevel()).thenReturn(true);
		when(topLevel.isInstance()).thenAnswer(inv -> instance);
		when(topLevel.getMapRegions()).thenAnswer(inv -> mapRegions);
		when(topLevel.getPlane()).thenReturn(playerLocation.getPlane());
		when(topLevel.getBaseX()).thenReturn(playerLocation.getX() - SCENE_SIZE / 2);
		when(topLevel.getBaseY()).thenReturn(playerLocation.getY() - SCENE_SIZE / 2);
		when(topLevel.getSizeX()).thenReturn(SCENE_SIZE);
		when(topLevel.getSizeY()).thenReturn(SCENE_SIZE);
		currentWorldView = topLevel;

		currentPlayer = mock(Player.class);
		when(currentPlayer.getWorldLocation()).thenReturn(playerLocation);

		ModelData modelData = mock(ModelData.class, RETURNS_SELF);
		when(modelData.light(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(mock(Model.class));

		client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenAnswer(inv -> gameState);
		when(client.getTopLevelWorldView()).thenAnswer(inv -> currentWorldView);
		when(client.getWorldView(anyInt())).thenAnswer(inv -> currentWorldView);
		when(client.getLocalPlayer()).thenAnswer(inv -> currentPlayer);
		when(client.loadModelData(anyInt())).thenReturn(modelData);
		when(client.mergeModels(any(ModelData[].class), anyInt())).thenReturn(modelData);
		when(client.createRuneLiteObject()).thenAnswer(inv -> newObject());

		Injector runelite = Guice.createInjector(binder -> {
			binder.bind(Client.class).toProvider(Providers.of(client));
			binder.bind(ClientThread.class).toProvider(Providers.of(mock(ClientThread.class)));
			binder.bind(Gson.class).toProvider(Providers.of(RuneLiteAPI.GSON));
			binder.bind(ChatMessageManager.class).toProvider(Providers.of(mock(ChatMessageManager.class)));
			binder.bind(OverlayManager.class).toProvider(Providers.of(mock(OverlayManager.class)));
			binder.bind(CitizensOverlay.class).toProvider(Providers.of(mock(CitizensOverlay.class)));
		});
		plugin = new CitizensPlugin();
		Injector pluginInjector = runelite.createChildInjector(binder -> {
			binder.bind(CitizensPlugin.class).toInstance(plugin);
			binder.install(plugin);
		});
		registry = pluginInjector.getInstance(CitizenRegistry.class);
	}

	@After
	public void noWarnings() {
		pluginLog.detachAppender(logged);
		logged.stop();
		List<String> warnings = logged.list.stream()
			.filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
			.map(ILoggingEvent::getFormattedMessage)
			.collect(Collectors.toList());
		Assert.assertEquals("Warnings logged", new ArrayList<String>(), warnings);
	}

	private static WorldPoint firstCitizenLocation() {
		CitizenRegion region = new CitizenRegistry(new CitizensPlugin(), RuneLiteAPI.GSON).loadRegion(REGION_ID);
		Assert.assertNotNull("No bundled region " + REGION_ID, region);
		Assert.assertFalse(region.citizenRoster.isEmpty());
		return region.citizenRoster.get(0).worldLocation;
	}

	/**
	 * A mocked object that keeps track of its registration with the client, as the real one does.
	 */
	private RuneLiteObject newObject() {
		RuneLiteObject object = mock(RuneLiteObject.class);
		AtomicBoolean registered = new AtomicBoolean();
		doAnswer(inv -> {
			boolean active = inv.getArgument(0);
			if (active) {
				registrations.incrementAndGet();
			}
			registered.set(active);
			return null;
		}).when(object).setActive(anyBoolean());
		when(object.isActive()).thenAnswer(inv -> registered.get());
		objects.add(object);
		return object;
	}

	private List<RuneLiteObject> registeredObjects() {
		return objects.stream().filter(RuneLiteObject::isActive).collect(Collectors.toList());
	}

	private void gameStateChanged(GameState state) {
		gameState = state;
		GameStateChanged event = new GameStateChanged();
		event.setGameState(state);
		plugin.onGameStateChanged(event);
	}

	/**
	 * Logs in next to the region's citizens and returns the objects that registered.
	 */
	private List<RuneLiteObject> loggedIn() {
		gameStateChanged(GameState.LOGGED_IN);
		List<RuneLiteObject> registered = registeredObjects();
		Assert.assertFalse("Nothing spawned on login", registered.isEmpty());
		return registered;
	}

	private static Set<Integer> ids(Integer... ids) {
		return new HashSet<>(Arrays.asList(ids));
	}

	@Test
	public void loggedInWithAPlayerLoadsTheSceneRegionsAndSpawns() {
		List<RuneLiteObject> registered = loggedIn();

		Assert.assertNotNull(registry.getRegion(REGION_ID));
		Assert.assertEquals(1, registry.getRegions().size());
		Assert.assertTrue(registry.isSyncedWith(ids(REGION_ID)));
		Assert.assertEquals(registered.size(), registrations.get());
	}

	@Test
	public void loadingDespawnsEverythingAndSpawnsNothing() {
		List<RuneLiteObject> registered = loggedIn();
		int registrationsBefore = registrations.get();

		gameStateChanged(GameState.LOADING);

		Assert.assertTrue(registeredObjects().isEmpty());
		for (RuneLiteObject object : registered) {
			verify(object).setActive(false);
		}
		Assert.assertEquals(registrationsBefore, registrations.get());
		// The regions stay loaded until the scene is known again
		Assert.assertNotNull(registry.getRegion(REGION_ID));
	}

	@Test
	public void loggedInWithoutALocalPlayerLoadsNothing() {
		currentPlayer = null;

		gameStateChanged(GameState.LOGGED_IN);

		Assert.assertTrue(registry.isEmpty());
		verify(client, never()).createRuneLiteObject();
	}

	@Test
	public void loggedInWithoutATopLevelWorldViewLoadsNothing() {
		currentWorldView = null;

		gameStateChanged(GameState.LOGGED_IN);

		Assert.assertTrue(registry.isEmpty());
		verify(client, never()).createRuneLiteObject();
	}

	@Test
	public void anInstanceUnloadsEveryRegion() {
		loggedIn();

		// Instances reuse the region ids of the areas they copy
		instance = true;
		gameStateChanged(GameState.LOADING);
		gameStateChanged(GameState.LOGGED_IN);

		Assert.assertTrue(registry.isEmpty());
		Assert.assertTrue(registeredObjects().isEmpty());
		Assert.assertTrue(registry.isSyncedWith(ids()));
	}

	@Test
	public void aGameTickAfterAnUnannouncedSceneChangeDespawnsAndReconciles() {
		List<RuneLiteObject> registered = loggedIn();
		int regionWithoutData = 1;
		Assert.assertNull(CitizenRegistry.class.getResource("/RegionData/" + regionWithoutData + ".json"));

		// The scene changed without any event
		mapRegions = new int[]{REGION_ID, regionWithoutData};
		plugin.onGameTick(new GameTick());

		// Every object came down before the reconcile put the ones in range back up
		for (RuneLiteObject object : registered) {
			verify(object).setActive(false);
		}
		Assert.assertEquals(registered, registeredObjects());
		Assert.assertTrue(registry.isSyncedWith(ids(REGION_ID, regionWithoutData)));

		// In sync again: the next tick takes nothing down
		plugin.onGameTick(new GameTick());
		for (RuneLiteObject object : registered) {
			verify(object, times(1)).setActive(false);
		}
		Assert.assertEquals(registered, registeredObjects());
	}

	@Test
	public void worldViewEventsOfOtherWorldViewsAreIgnored() {
		List<RuneLiteObject> registered = loggedIn();
		int registrationsBefore = registrations.get();
		WorldView boat = mock(WorldView.class);
		when(boat.isTopLevel()).thenReturn(false);

		plugin.onWorldViewUnloaded(new WorldViewUnloaded(boat));
		plugin.onWorldViewLoaded(new WorldViewLoaded(boat));

		for (RuneLiteObject object : registered) {
			verify(object, never()).setActive(false);
		}
		Assert.assertEquals(registered, registeredObjects());
		Assert.assertEquals(registrationsBefore, registrations.get());
		Assert.assertNotNull(registry.getRegion(REGION_ID));
	}

	@Test
	public void theTopLevelWorldViewUnloadingDespawnsEverything() {
		List<RuneLiteObject> registered = loggedIn();

		plugin.onWorldViewUnloaded(new WorldViewUnloaded(topLevel));

		Assert.assertTrue(registeredObjects().isEmpty());
		for (RuneLiteObject object : registered) {
			verify(object).setActive(false);
		}
	}
}
