package com.magnaboy;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

public class CitizensOverlay extends Overlay {
	private final CitizensPlugin plugin;
	private final CitizenRegistry registry;

	Font overheadFont = FontManager.getRunescapeBoldFont();

	@Inject
	public CitizensOverlay(CitizensPlugin plugin, CitizenRegistry registry) {
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		this.plugin = plugin;
		this.registry = registry;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		WorldView wv = plugin.client.getTopLevelWorldView();
		if (wv == null) {
			return null;
		}

		registry.forEachActiveEntity((entity) -> {
			if (!entity.isActive() || !entity.isCitizen()) {
				return;
			}

			Citizen citizen = (Citizen) entity;
			LocalPoint localLocation = citizen.getLocalLocation();

			if (!citizen.shouldRender() || localLocation == null) {
				return;
			}

			// Render remarks
			if (citizen.activeRemark != null) {
				Point p = Perspective.localToCanvas(plugin.client, citizen.getLocalLocation(), wv.getPlane(),
					citizen.getModelHeight());
				if (p != null) {
					graphics.setFont(overheadFont);
					FontMetrics metrics = graphics.getFontMetrics(overheadFont);
					Point shiftedP = new Point(p.getX() - (metrics.stringWidth(citizen.activeRemark) / 2), p.getY());
					OverlayUtil.renderTextLocation(graphics, shiftedP, citizen.activeRemark,
						JagexColors.YELLOW_INTERFACE_TEXT);
				}
			}
		});

		return null;
	}
}
