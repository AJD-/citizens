package com.magnaboy;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * The editor's debug drawing: the selected tile, an outline around the selected entity, and for each citizen within
 * {@value #DEBUG_DISTANCE} tiles a debug line (name, type, id, distance, current script action, model height) and its
 * walk target tile. Overlays render on the client thread, so it reads the editor's selection directly.
 */
class CitizensDevOverlay extends Overlay {
	private static final int DEBUG_DISTANCE = 15;
	private static final Color SELECTED_COLOR = new Color(0, 255, 255, 200);
	private static final Color TARGET_COLOR = new Color(235, 150, 52);

	private final Client client;
	private final CitizensDevPlugin devPlugin;
	private final CitizenRegistry registry;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	CitizensDevOverlay(Client client, CitizensDevPlugin devPlugin, CitizenRegistry registry,
		ModelOutlineRenderer modelOutlineRenderer) {
		this.client = client;
		this.devPlugin = devPlugin;
		this.registry = registry;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	private void renderText(Graphics2D graphics, WorldView wv, LocalPoint lp, String text, Color color) {
		Font overheadFont = FontManager.getRunescapeSmallFont();
		graphics.setFont(overheadFont);

		Point p = Perspective.localToCanvas(client, lp, wv.getPlane(), 0);
		if (p == null) {
			return;
		}
		FontMetrics metrics = graphics.getFontMetrics(overheadFont);
		Point shiftedP = new Point(p.getX() - (metrics.stringWidth(text) / 2), p.getY());
		OverlayUtil.renderTextLocation(graphics, shiftedP, text, color);
	}

	private void highlightTile(Graphics2D graphics, LocalPoint lp, Color color) {
		if (lp == null) {
			return;
		}
		final Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null) {
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null) {
			return null;
		}

		WorldPoint selectedPosition = devPlugin.getSelectedPosition();
		if (selectedPosition != null) {
			LocalPoint lp = LocalPoint.fromWorld(wv, selectedPosition);
			if (lp != null) {
				highlightTile(graphics, lp, SELECTED_COLOR);
				renderText(graphics, wv, lp, "Selected Tile", SELECTED_COLOR);
			}
		}

		Entity selectedEntity = devPlugin.getSelectedEntity();
		if (selectedEntity != null && selectedEntity.rlObject != null && selectedEntity.isActive()) {
			final int outlineWidth = 4;
			modelOutlineRenderer.drawOutline(selectedEntity.rlObject, outlineWidth, Color.cyan, outlineWidth - 2);
		}

		registry.forEachActiveEntity((entity) -> {
			if (!entity.isCitizen()) {
				return;
			}

			Citizen citizen = (Citizen) entity;
			LocalPoint localLocation = citizen.getLocalLocation();

			if (!citizen.shouldRender() || localLocation == null || citizen.distanceToPlayer() >= DEBUG_DISTANCE) {
				return;
			}

			String extraString = "";
			if (citizen.entityType == EntityType.ScriptedCitizen) {
				ScriptedCitizen scriptedCitizen = (ScriptedCitizen) citizen;
				if (scriptedCitizen.currentAction != null && scriptedCitizen.currentAction.action != null) {
					extraString = scriptedCitizen.currentAction.action + " ";
				}
			}
			String debugText = citizen.debugName() + " " + extraString + "H:" + citizen.getModelHeight() + " ";
			renderText(graphics, wv, localLocation, debugText, JagexColors.YELLOW_INTERFACE_TEXT);
			Citizen.Target target = citizen.getCurrentTarget();
			if (target != null) {
				highlightTile(graphics, target.localDestinationPosition, TARGET_COLOR);
			}
		});

		return null;
	}
}
