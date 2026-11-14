package com.magnaboy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AABB;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.api.model.Jarvis;

/**
 * A fake NPC or piece of scenery backed by a {@link RuneLiteObject}.
 * <p>
 * Concurrency: constructors make no client calls. The RuneLiteObject is created lazily on the client thread at the
 * first successful {@link #spawn()}, and every method that touches it or the client runs on the client thread.
 * The fields are read and written on the client thread only.
 */
@Slf4j
public class Entity<T extends Entity<T>> {
	private static final int MAX_MODEL_LOAD_ATTEMPTS = 50;

	public Integer regionId;
	public String name;
	public String examine;
	public CitizensPlugin plugin;
	public AnimationID idleAnimationId;
	public float[] scale;
	public float[] translate;
	public List<MergedObject> mergedObjects = new ArrayList<>();
	/**
	 * Null until the first successful spawn.
	 */
	protected RuneLiteObject rlObject;
	@Getter
	protected EntityType entityType;
	protected Integer baseOrientation;
	protected UUID uuid;
	/**
	 * The tile the entity is on: its configured location until spawned, then kept in step with the object by
	 * {@link #setLocation(LocalPoint)}.
	 */
	@Getter
	private WorldPoint worldLocation;
	private int[] modelIDs;
	private int[] recolorsToFind;
	private int[] recolorsToReplace;

	// Mirrors of the object's state, kept so that they can be read before the object exists
	private boolean active;
	private int animationId = -1;
	private int orientation;

	@Getter
	private int modelHeight;
	private int modelLoadAttempts;
	private boolean modelLoadFailed;

	public Entity(CitizensPlugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * This entity as its own concrete type, for the chained setters.
	 */
	@SuppressWarnings("unchecked") // T is always the subclass that extends Entity<T>
	protected T self() {
		return (T) this;
	}

	protected static SimplePolygon calculateAABB(Client client, WorldView wv, Model m, Integer jauOrient, int x, int y, int z) {
		if (m == null) {
			throw new IllegalStateException("model is null");
		}
		if (jauOrient == null) {
			throw new IllegalStateException("jauOrient is null");
		}
		AABB aabb = m.getAABB(jauOrient);

		int x1 = aabb.getCenterX();
		int y1 = aabb.getCenterZ();
		int z1 = aabb.getCenterY();

		int ex = aabb.getExtremeX();
		int ey = aabb.getExtremeZ();
		int ez = aabb.getExtremeY();

		int x2 = x1 + ex;
		int y2 = y1 + ey;
		int z2 = z1 + ez;

		x1 -= ex;
		y1 -= ey;
		z1 -= ez;

		float[] xa = new float[]{x1, x2, x1, x2, x1, x2, x1, x2};
		float[] ya = new float[]{y1, y1, y2, y2, y1, y1, y2, y2};
		float[] za = new float[]{z1, z1, z1, z1, z2, z2, z2, z2};

		int[] x2d = new int[8];
		int[] y2d = new int[8];

		// Use RuneLite's own projection; camera angles are no longer in 2048-unit JAU
		Perspective.modelToCanvas(client, wv, 8, x, y, z, 0, xa, ya, za, x2d, y2d);

		return Jarvis.convexHull(x2d, y2d);
	}

	/**
	 * The id of the animation last set on the object, or -1 if none has been set.
	 */
	public int getAnimationID() {
		return animationId;
	}

	public boolean isCitizen() {
		return entityType == EntityType.StationaryCitizen || entityType == EntityType.WanderingCitizen || entityType == EntityType.ScriptedCitizen;
	}

	public SimplePolygon getClickbox() {
		WorldView wv = plugin.client.getTopLevelWorldView();
		LocalPoint location = getLocalLocation();
		if (wv == null || location == null) {
			return null;
		}
		int tileHeight = Perspective.getTileHeight(plugin.client, location, wv.getPlane());
		return calculateAABB(plugin.client, wv, rlObject.getBaseModel(), rlObject.getOrientation(), location.getX(), location.getY(), tileHeight);
	}

	/**
	 * Client thread only. Null when the object does not exist yet.
	 */
	public LocalPoint getLocalLocation() {
		if (rlObject == null) {
			return null;
		}
		return rlObject.getLocation();
	}

	/**
	 * Client thread only. Does nothing before the object exists.
	 */
	public void setOrientation(int orientation) {
		if (rlObject == null) {
			return;
		}
		rlObject.setOrientation(orientation);
		this.orientation = orientation;
	}

	/**
	 * Client thread only. Does nothing before the object exists.
	 */
	public void setAnimation(int animationID) {
		if (rlObject == null) {
			return;
		}
		Animation anim = plugin.client.loadAnimation(animationID);
		rlObject.setAnimation(anim);
		this.animationId = animationID;
	}

	/**
	 * Client thread only. Plays an animation that calls {@code onFinished} at its end instead of looping. Does nothing
	 * before the object exists or when the animation does not load.
	 *
	 * @return the animation played, or null if nothing changed
	 */
	protected Animation setAnimation(int animationID, Consumer<AnimationController> onFinished) {
		if (rlObject == null) {
			return null;
		}
		Animation anim = plugin.client.loadAnimation(animationID);
		if (anim == null) {
			return null;
		}
		AnimationController controller = new AnimationController(plugin.client, anim);
		controller.setOnFinished(onFinished);
		rlObject.setAnimationController(controller);
		this.animationId = animationID;
		return anim;
	}

	public T setWorldLocation(WorldPoint location) {
		this.worldLocation = location;
		return self();
	}

	public T addMergedObject(MergedObject mergedObject) {
		this.mergedObjects.add(mergedObject);
		return self();
	}

	/**
	 * Client thread only. Runs every game tick: spawns or despawns the entity depending on whether it should render.
	 */
	public void update() {
		assert plugin.client.isClientThread();

		// Pick up objects the client has removed by itself
		active = rlObject != null && rlObject.isActive();

		boolean inScene = shouldRender();

		if (inScene) {
			spawn();
		} else {
			despawn();
		}
	}

	public T setScale(float[] scale) {
		this.scale = scale;
		return self();
	}

	public T setTranslate(float translateX, float translateY, float translateZ) {
		this.translate = new float[]{translateX, translateY, translateZ};
		return self();
	}

	public T setTranslate(float[] translate) {
		this.translate = translate;
		return self();
	}

	public T setBaseOrientation(CardinalDirection baseOrientation) {
		this.baseOrientation = baseOrientation.getAngle();
		return self();
	}

	public T setBaseOrientation(Integer baseOrientation) {
		this.baseOrientation = baseOrientation;
		return self();
	}

	public T setModelIDs(int[] modelIDs) {
		this.modelIDs = modelIDs;
		return self();
	}

	public T setModelRecolors(int[] recolorsToFind, int[] recolorsToReplace) {
		this.recolorsToFind = recolorsToFind;
		this.recolorsToReplace = recolorsToReplace;
		return self();
	}

	/**
	 * Client thread only. The object must exist.
	 */
	public T setLocation(LocalPoint location) {
		if (location == null) {
			throw new IllegalStateException("Tried to set null location");
		}
		rlObject.setLocation(location, getPlane());
		WorldPoint wp = WorldPoint.fromLocal(plugin.client, location);
		setWorldLocation(wp);
		return self();
	}

	public int getPlane() {
		return this.worldLocation.getPlane();
	}

	/**
	 * Client thread only.
	 */
	public boolean shouldRender() {
		WorldView wv = plugin.client.getTopLevelWorldView();
		if (wv == null) {
			return false;
		}

		if (getPlane() != wv.getPlane()) {
			return false;
		}

		float distanceFromPlayer = distanceToPlayer();

		if (distanceFromPlayer > Util.MAX_ENTITY_RENDER_DISTANCE) {
			return false;
		}

		LocalPoint lp = LocalPoint.fromWorld(wv, worldLocation);
		return lp != null;
	}

	/**
	 * Client thread only. {@link Float#MAX_VALUE} when there is no local player.
	 */
	public float distanceToPlayer() {
		Player player = plugin.client.getLocalPlayer();
		if (player == null) {
			return Float.MAX_VALUE;
		}
		WorldPoint playerWorldLoc = player.getWorldLocation();
		return playerWorldLoc.distanceTo(getWorldLocation());
	}

	/**
	 * Client thread only. Removes the object from the scene. Safe to call on an entity that is not spawned.
	 *
	 * @return whether the entity was active
	 */
	public boolean despawn() {
		assert plugin.client.isClientThread();

		if (rlObject == null) {
			return false;
		}

		boolean wasActive = active;
		// Ask the client rather than the flag: a registration the flag missed would otherwise outlive the scene
		if (rlObject.isActive()) {
			rlObject.setActive(false);
		}
		active = false;

		return wasActive;
	}

	/**
	 * Builds the model from the cache. Returns null, without changing any loaded data, if a model is not loaded yet.
	 */
	private Model buildModel() {
		List<ModelData> models = new ArrayList<>();
		for (int modelID : modelIDs) {
			ModelData data = plugin.client.loadModelData(modelID);
			if (data == null) {
				onModelLoadFailure(modelID);
				return null;
			}
			models.add(data);
		}

		List<ModelData> mergedModels = new ArrayList<>();
		for (MergedObject obj : mergedObjects) {
			ModelData data = plugin.client.loadModelData(obj.objectID);
			if (data == null) {
				onModelLoadFailure(obj.objectID);
				return null;
			}
			mergedModels.add(data);
		}

		// Merge merged objects
		for (int m = 0; m < mergedObjects.size(); m++) {
			ModelData data = mergedModels.get(m);
			for (int i = 0; i < mergedObjects.get(m).count90CCWRotations; i++) {
				data.cloneVertices();
				data.rotateY90Ccw();
			}
			models.add(data);
		}

		ModelData finalModel = plugin.client.mergeModels(models.toArray(new ModelData[models.size()]), models.size());
		if (recolorsToReplace != null && recolorsToReplace.length > 0) {
			for (int i = 0; i < recolorsToReplace.length; i++) {
				finalModel.recolor((short) recolorsToFind[i], (short) recolorsToReplace[i]);
			}
		}
		if (scale != null) {
			finalModel.cloneVertices();
			finalModel.scale(-(Math.round(scale[0] * 128)), -(Math.round(scale[1] * 128)), -(Math.round(scale[2] * 128)));
		}

		if (translate != null) {
			finalModel.cloneVertices();
			finalModel.translate(-(Math.round(translate[0] * 128)), -(Math.round(translate[1] * 128)), -(Math.round(translate[2] * 128)));
		}

		return finalModel.light(64, 850, -30, -50, -30);
	}

	private void onModelLoadFailure(int modelID) {
		modelLoadAttempts++;
		if (modelLoadAttempts >= MAX_MODEL_LOAD_ATTEMPTS) {
			modelLoadFailed = true;
			log.warn("{} will not be spawned: model {} did not load after {} attempts", debugName(), modelID,
				MAX_MODEL_LOAD_ATTEMPTS);
		}
	}

	public String debugName() {
		float dist = distanceToPlayer();
		return "N:" + name + " T:" + entityType + " ID:" + uuid.toString().substring(0, 6) + " D:" + dist;
	}

	/**
	 * Client thread only. Builds the model and creates the object on first use, then places and shows it. If a
	 * model is not loaded yet nothing is shown and the next {@link #update()} tries again.
	 *
	 * @return whether the entity was spawned
	 */
	public boolean spawn() {
		assert plugin.client.isClientThread();

		if (active || modelLoadFailed) {
			return false;
		}

		WorldView wv = plugin.client.getTopLevelWorldView();
		if (wv == null) {
			return false;
		}
		LocalPoint initialLocation = LocalPoint.fromWorld(wv, worldLocation);
		if (initialLocation == null) {
			return false;
		}

		if (rlObject == null) {
			Model model = buildModel();
			if (model == null) {
				return false;
			}
			rlObject = plugin.client.createRuneLiteObject();
			rlObject.setModel(model);
			// The height is only valid once the bounds are calculated
			model.calculateBoundsCylinder();
			modelHeight = model.getModelHeight();
		}

		setLocation(initialLocation);

		if (baseOrientation != null && orientation == 0) {
			setOrientation(baseOrientation);
		}
		if (idleAnimationId != null) {
			setAnimation(idleAnimationId.getId());
		}

		rlObject.setActive(true);
		active = true;

		return true;
	}

	public boolean isActive() {
		return active;
	}

	/**
	 * Client thread only.
	 */
	public boolean rotateObject(double intx, double inty) {
		if (intx == 0 && inty == 0) {
			return true;
		}
		int targetOrientation = Util.radToJau(Math.atan2(intx, inty));
		int currentOrientation = rlObject.getOrientation();

		int dJau = (targetOrientation - currentOrientation) % Util.JAU_FULL_ROTATION;
		if (dJau != 0) {
			final int JAU_HALF_ROTATION = 1024;
			final int JAU_TURN_SPEED = 32;
			int dJauCW = Math.abs(dJau);

			if (dJauCW > JAU_HALF_ROTATION) {
				dJau = (currentOrientation - targetOrientation) % Util.JAU_FULL_ROTATION;
			} else if (dJauCW == JAU_HALF_ROTATION) {
				dJau = dJauCW;
			}

			if (Math.abs(dJau) > JAU_TURN_SPEED) {
				dJau = Integer.signum(dJau) * JAU_TURN_SPEED;
			}

			int newOrientation = (Util.JAU_FULL_ROTATION + rlObject.getOrientation() + dJau) % Util.JAU_FULL_ROTATION;

			setOrientation(newOrientation);
			dJau = (targetOrientation - newOrientation) % Util.JAU_FULL_ROTATION;
		}

		return dJau == 0;
	}

	public T setIdleAnimation(AnimationID idleAnimationId) {
		this.idleAnimationId = idleAnimationId;
		return self();
	}

	public T setUUID(UUID uuid) {
		if (this.uuid == null) {
			this.uuid = uuid;
		}
		return self();
	}

	public T setRegion(int regionId) {
		this.regionId = regionId;
		return self();
	}

	@Override
	public boolean equals(Object o) {
		if (o == this) {
			return true;
		}

		if (!(o instanceof Entity)) {
			return false;
		}

		Entity compare = (Entity) o;
		return Objects.equals(this.uuid, compare.uuid);
	}

	@Override
	public int hashCode() {
		return Objects.hash(uuid);
	}
}
