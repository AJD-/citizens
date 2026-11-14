package com.magnaboy;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

public class CitizensOverlay extends Overlay {
	private final CitizensPlugin plugin;
	private final CitizenRegistry registry;
	private final ModelOutlineRenderer modelOutlineRenderer;

	Font overheadFont = FontManager.getRunescapeBoldFont();

	@Inject
	public CitizensOverlay(CitizensPlugin plugin, CitizenRegistry registry, ModelOutlineRenderer modelOutlineRenderer) {
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		this.plugin = plugin;
		this.registry = registry;
	}

	private void renderText(Graphics2D graphics, WorldView wv, LocalPoint lp, String text) {
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}
		renderText(graphics, wv, lp, text, new Color(0, 255, 3));
	}

	private void renderText(Graphics2D graphics, WorldView wv, LocalPoint lp, String text, Color color) {
		Font overheadFont = FontManager.getRunescapeSmallFont();
		graphics.setFont(overheadFont);

		Point p = Perspective.localToCanvas(plugin.client, lp, wv.getPlane(), 0);
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
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}
		final Polygon poly = Perspective.getCanvasTilePoly(plugin.client, lp);
		if (poly != null) {
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	private void highlightTile(Graphics2D graphics, WorldView wv, WorldPoint wp, Color color) {
		if (!plugin.IS_DEVELOPMENT) {
			return;
		}
		LocalPoint lp = LocalPoint.fromWorld(wv, wp);
		if (lp == null) {
			return;
		}
		final Polygon poly = Perspective.getCanvasTilePoly(plugin.client, lp);
		if (poly != null) {
			OverlayUtil.renderPolygon(graphics, poly, color);
		}
	}

	private void highlightRegion(Graphics2D graphics, WorldView wv, WorldPoint bottomLeft, WorldPoint topRight, int plane, Color color) {
		WorldArea boundingBox = Util.calculateBoundingBox(bottomLeft, topRight);
		highlightRegion(graphics, wv, boundingBox, plane, color);
	}

	private void highlightRegion(Graphics2D graphics, WorldView wv, WorldArea boundingBox, int plane, Color color) {
		int x = boundingBox.getX();
		int y = boundingBox.getY();
		for (int i = y; i <= y + boundingBox.getHeight(); i++) {
			for (int t = 0; t <= boundingBox.getWidth(); t++) {
				highlightTile(graphics, wv, new WorldPoint(x + t, i, plane), color);
			}
		}
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (CitizensPlugin.shuttingDown) {
			return null;
		}

		WorldView wv = plugin.client.getTopLevelWorldView();
		if (wv == null) {
			return null;
		}

		if (CitizenPanel.selectedPosition != null) {
			Color selectedColor = new Color(0, 255, 255, 200);
			highlightTile(graphics, wv, CitizenPanel.selectedPosition, selectedColor);
			LocalPoint lp = LocalPoint.fromWorld(wv, CitizenPanel.selectedPosition);
			if (plugin.IS_DEVELOPMENT && lp != null) {
				renderText(graphics, wv, lp, "Selected Tile", selectedColor);
			}
		}

		Entity selectedEntity = CitizenPanel.selectedEntity;
		if (selectedEntity != null && selectedEntity.rlObject != null) {
			final int outlineWidth = 4;
			modelOutlineRenderer.drawOutline(selectedEntity.rlObject, outlineWidth, Color.cyan, outlineWidth - 2);
		}

		registry.forEachActiveEntity((entity) -> {
			if (!entity.isCitizen()) {
				return;
			}

			Citizen citizen = (Citizen) entity;
			LocalPoint localLocation = citizen.getLocalLocation();

			if (!citizen.shouldRender() || localLocation == null) {
				return;
			}

			// Render remarks
			if (citizen.activeRemark != null) {
				Point p = Perspective.localToCanvas(plugin.client, citizen.getLocalLocation(), wv.getPlane(), citizen
					.rlObject.getModel().getModelHeight());
				if (p != null) {
					graphics.setFont(overheadFont);
					FontMetrics metrics = graphics.getFontMetrics(overheadFont);
					Point shiftedP = new Point(p.getX() - (metrics.stringWidth(citizen.activeRemark) / 2), p.getY());
					OverlayUtil.renderTextLocation(graphics, shiftedP, citizen.activeRemark,
						JagexColors.YELLOW_INTERFACE_TEXT);
				}
			}

			if (plugin.IS_DEVELOPMENT && citizen.distanceToPlayer() < 15) {
				String extraString = "";
				if (citizen.entityType == EntityType.ScriptedCitizen) {
					ScriptedCitizen scriptedCitizen = (ScriptedCitizen) citizen;
					if (scriptedCitizen.currentAction != null && scriptedCitizen.currentAction.action != null) {
						extraString = scriptedCitizen.currentAction.action + " ";
					}
				}
				String debugText = citizen.debugName() + " " + extraString + "H:" + citizen.rlObject.getModel().getModelHeight() + " ";
				renderText(graphics, wv, localLocation, debugText, JagexColors.YELLOW_INTERFACE_TEXT);
				Citizen.Target target = citizen.getCurrentTarget();
				if (target != null) {
					highlightTile(graphics, target.localDestinationPosition, new Color(235, 150, 52));
				}
			}
		});

		return null;
	}
}
