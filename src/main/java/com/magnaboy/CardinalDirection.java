package com.magnaboy;

import lombok.Getter;

@Getter
public enum CardinalDirection {
	SouthEast(1792),
	South(0),
	SouthWest(256),
	West(512),
	North(1024),
	NorthWest(768),
	East(1536);

	private final int angle;

	CardinalDirection(int angle) {
		this.angle = angle;
	}

}
