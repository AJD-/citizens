package com.magnaboy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import net.runelite.api.AABB;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.geometry.SimplePolygon;
import net.runelite.api.model.Jarvis;

public class Entity<T extends Entity<T>> {
	public Integer regionId;
	public String name;
	public String examine;
	public CitizensPlugin plugin;
	public AnimationID idleAnimationId;
	public float[] scale;
	public float[] translate;
	public List<MergedObject> mergedObjects = new ArrayList<>();
	protected RuneLiteObject rlObject;
	@Getter
	protected EntityType entityType;
	protected Integer baseOrientation;
	protected UUID uuid;
	@Getter
	private WorldPoint worldLocation;
	private int[] modelIDs;
	private int[] recolorsToFind;
	private int[] recolorsToReplace;
	private Integer objectToRemove;

	public Entity(CitizensPlugin plugin) {
		this.plugin = plugin;
		this.rlObject = plugin.client.createRuneLiteObject();
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

	public int getAnimationID() {
		return rlObject.getAnimation().getId();
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
		return calculateAABB(plugin.client, wv, rlObject.getModel(), rlObject.getOrientation(), location.getX(), location.getY(), tileHeight);
	}

	public LocalPoint getLocalLocation() {
		return rlObject.getLocation();
	}

	public int getOrientation() {
		return rlObject.getOrientation();
	}

	public void setModel(Model model) {
		rlObject.setModel(model);
	}

	public void setAnimation(int animationID) {
		plugin.clientThread.invokeLater(() -> {
			Animation anim = plugin.client.loadAnimation(animationID);
			rlObject.setAnimation(anim);
		});
	}

	public T setWorldLocation(WorldPoint location) {
		this.worldLocation = location;
		return (T) this;
	}

	public T setObjectToRemove(Integer objectToRemove) {
		this.objectToRemove = objectToRemove;
		return (T) this;
	}

	public T addMergedObject(MergedObject mergedObject) {
		this.mergedObjects.add(mergedObject);
		return (T) this;
	}

	public void update() {
		boolean inScene = shouldRender();

		if (inScene) {
			spawn();
		} else {
			despawn();
		}
	}

	public T setScale(float[] scale) {
		this.scale = scale;
		return (T) this;
	}

	public T setTranslate(float translateX, float translateY, float translateZ) {
		this.translate = new float[]{translateX, translateY, translateZ};
		return (T) this;
	}

	public T setTranslate(float[] translate) {
		this.translate = translate;
		return (T) this;
	}

	public T setBaseOrientation(CardinalDirection baseOrientation) {
		this.baseOrientation = baseOrientation.getAngle();
		return (T) this;
	}

	public T setBaseOrientation(Integer baseOrientation) {
		this.baseOrientation = baseOrientation;
		return (T) this;
	}

	public T setModelIDs(int[] modelIDs) {
		this.modelIDs = modelIDs;
		return (T) this;
	}

	public T setModelRecolors(int[] recolorsToFind, int[] recolorsToReplace) {
		this.recolorsToFind = recolorsToFind;
		this.recolorsToReplace = recolorsToReplace;
		return (T) this;
	}

	public T setLocation(LocalPoint location) {
		if (location == null) {
			throw new IllegalStateException("Tried to set null location");
		}
		rlObject.setLocation(location, getPlane());
		WorldPoint wp = WorldPoint.fromLocal(plugin.client, location);
		setWorldLocation(wp);
		return (T) this;
	}

	public int getPlane() {
		return this.worldLocation.getPlane();
	}

	public boolean shouldRender() {
		if (getPlane() != plugin.client.getPlane()) {
			return false;
		}

		float distanceFromPlayer = distanceToPlayer();

		if (distanceFromPlayer > Util.MAX_ENTITY_RENDER_DISTANCE) {
			return false;
		}

		LocalPoint lp = LocalPoint.fromWorld(plugin.client, worldLocation);
		return lp != null;
	}

	public float distanceToPlayer() {
		Player player = plugin.client.getLocalPlayer();
		if (player == null) {
			return Float.MAX_VALUE;
		}
		WorldPoint playerWorldLoc = player.getWorldLocation();
		return playerWorldLoc.distanceTo(getWorldLocation());
	}

	public boolean despawn() {
		if (rlObject == null) {
			return false;
		}
		if (!rlObject.isActive()) {
			return false;
		}

		plugin.clientThread.invokeLater(() -> {
			rlObject.setActive(false);
		});

		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
		}

		return true;
	}

	private void initModel() {
		if (rlObject.getModel() == null) {
			ArrayList<ModelData> models = new ArrayList<ModelData>();
			for (int modelID : modelIDs) {
				ModelData data = plugin.client.loadModelData(modelID);
				models.add(data);
			}

			// Merge merged objects
			for (MergedObject obj : mergedObjects) {
				ModelData data = plugin.client.loadModelData(obj.objectID);
				for (int i = 0; i < obj.count90CCWRotations; i++) {
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

			rlObject.setModel(finalModel.light(64, 850, -30, -50, -30));
		}

		if (baseOrientation != null && rlObject.getOrientation() == 0) {
			rlObject.setOrientation(baseOrientation);
		}

		if (this.idleAnimationId != null && rlObject.getAnimation() == null) {
			setAnimation((this.idleAnimationId).getId());
		}

		rlObject.setShouldLoop(true);
	}

	public String debugName() {
		float dist = distanceToPlayer();
		return "N:" + name + " T:" + entityType + " ID:" + uuid.toString().substring(0, 6) + " D:" + dist;
	}

	public void validate() {
		if (uuid == null) {
			throw new IllegalStateException(debugName() + " has no uuid.");
		}
		if (regionId == null) {
			throw new IllegalStateException(debugName() + " has no regionId.");
		}
	}

	private void initLocation() {
		LocalPoint initializedLocation = LocalPoint.fromWorld(plugin.client, worldLocation);
		if (initializedLocation == null) {
			throw new IllegalStateException("Tried to spawn entity with no initializedLocation: " + debugName());
		}
		setLocation(initializedLocation);
	}

	public boolean spawn() {
		if (this.isActive()) {
			return false;
		}

		initModel();
		initLocation();
		if (objectToRemove != null) {
			removeOtherObjects();
		}

		if (idleAnimationId != null) {
			setAnimation(idleAnimationId.getId());
		}

		plugin.clientThread.invokeLater(() -> {
			rlObject.setActive(true);
		});

		if (plugin.IS_DEVELOPMENT) {
			plugin.panel.update();
		}

		return true;
	}

	public boolean isActive() {
		return rlObject.isActive();
	}

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

			rlObject.setOrientation(newOrientation);
			dJau = (targetOrientation - newOrientation) % Util.JAU_FULL_ROTATION;
		}

		return dJau == 0;
	}

	public T setIdleAnimation(AnimationID idleAnimationId) {
		this.idleAnimationId = idleAnimationId;
		return (T) this;
	}

	public T setUUID(UUID uuid) {
		if (this.uuid == null) {
			this.uuid = uuid;
		}
		return (T) this;
	}

	public T setRegion(int regionId) {
		this.regionId = regionId;
		return (T) this;
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
		return this.uuid == compare.uuid;
	}

	public String getModelIDsString() {
		return Util.intArrayToString(modelIDs);
	}

	public String getRecolorFindString() {
		return Util.intArrayToString(recolorsToFind);
	}

	public String getRecolorReplaceString() {
		return Util.intArrayToString(recolorsToReplace);
	}

	private void removeOtherObjects() {
		Scene scene = plugin.client.getScene();
		Tile[][] tiles = scene.getTiles()[plugin.client.getPlane()];

		LocalPoint lp = LocalPoint.fromWorld(plugin.client, worldLocation);
		if (lp == null) {
			return;
		}
		Tile tile = tiles[lp.getSceneX()][lp.getSceneY()];
		if (tile == null) {
			return;
		}

		for (GameObject gameObject : tile.getGameObjects()) {
			if (gameObject == null) {
				continue;
			}
			if (gameObject.getId() == objectToRemove) {
				// Currently it's not possible to re-add the Game Object outside of an area load
				scene.removeGameObject(gameObject);
			}
		}

	}
}
