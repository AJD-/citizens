package com.magnaboy.tools.cachediff;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;

/**
 * Hashes a model's geometry and face colours: vertex and face counts, vertex positions, face
 * indices and face colours. Two models with the same hash look the same (ignoring textures and
 * render priorities), whatever their id.
 */
final class ModelHash {
	private ModelHash() {
	}

	static String of(int vertexCount, int faceCount, int[] x, int[] y, int[] z,
		int[] face1, int[] face2, int[] face3, short[] faceColors) {
		MessageDigest d;
		try {
			d = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		d.update(ByteBuffer.allocate(8).putInt(vertexCount).putInt(faceCount).array());
		put(d, x, vertexCount);
		put(d, y, vertexCount);
		put(d, z, vertexCount);
		put(d, face1, faceCount);
		put(d, face2, faceCount);
		put(d, face3, faceCount);
		if (faceColors == null) {
			d.update((byte) 0);
		} else {
			ByteBuffer b = ByteBuffer.allocate(2 * faceCount);
			for (int i = 0; i < faceCount; i++) {
				b.putShort(faceColors[i]);
			}
			d.update(b.array());
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : d.digest()) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/** Distinct face colours as unsigned 16-bit values, sorted. */
	static int[] colors(int faceCount, short[] faceColors) {
		TreeSet<Integer> set = new TreeSet<>();
		if (faceColors != null) {
			for (int i = 0; i < faceCount; i++) {
				set.add(faceColors[i] & 0xFFFF);
			}
		}
		return set.stream().mapToInt(Integer::intValue).toArray();
	}

	private static void put(MessageDigest d, int[] a, int n) {
		if (a == null) {
			d.update((byte) 0);
			return;
		}
		ByteBuffer b = ByteBuffer.allocate(4 * n);
		for (int i = 0; i < n; i++) {
			b.putInt(a[i]);
		}
		d.update(b.array());
	}
}
