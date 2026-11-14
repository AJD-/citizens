package com.magnaboy.tools.cachediff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.KitDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.KitLoader;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Storage;
import net.runelite.cache.fs.Store;

/** A game cache directory read with RuneLite's cache library. Everything is loaded lazily. */
final class GameCache implements CacheView, AutoCloseable {
	static final class CacheException extends Exception {
		CacheException(String message) {
			super(message);
		}
	}

	private final Path dir;
	private final Store store;
	private final Storage storage;
	private final Map<Integer, ModelState> models = new HashMap<>();
	private Map<String, List<Integer>> modelsByHash;
	private Map<Integer, int[]> kits;
	private Map<Integer, NpcInfo> npcs;
	private Map<Integer, SequenceInfo> sequences;

	private GameCache(Path dir, Store store) {
		this.dir = dir;
		this.store = store;
		this.storage = store.getStorage();
	}

	static GameCache open(Path dir) throws CacheException {
		if (!Files.isDirectory(dir)) {
			throw new CacheException("not a directory: " + dir);
		}
		if (!Files.isRegularFile(dir.resolve("main_file_cache.dat2")) || !Files.isRegularFile(dir.resolve("main_file_cache.idx255"))) {
			throw new CacheException("not a game cache (no main_file_cache.dat2 / .idx255): " + dir);
		}
		Store store = null;
		try {
			store = new Store(dir.toFile());
			store.load();
			if (store.getIndex(IndexType.MODELS) == null || store.getIndex(IndexType.CONFIGS) == null) {
				throw new CacheException("cache has no model or config index: " + dir);
			}
			return new GameCache(dir, store);
		} catch (IOException | RuntimeException e) {
			closeQuietly(store);
			throw new CacheException("cannot read game cache " + dir + ": " + e);
		} catch (CacheException e) {
			closeQuietly(store);
			throw e;
		}
	}

	private static void closeQuietly(Store store) {
		if (store != null) {
			try {
				store.close();
			} catch (IOException ignored) {
				// nothing useful to do
			}
		}
	}

	@Override
	public void close() {
		closeQuietly(store);
	}

	@Override
	public ModelState model(int id) {
		return models.computeIfAbsent(id, this::loadModel);
	}

	private ModelState loadModel(int id) {
		Archive archive = store.getIndex(IndexType.MODELS).getArchive(id);
		if (archive == null) {
			return ModelState.absent();
		}
		try {
			byte[] data = archive.decompress(storage.loadArchive(archive));
			ModelDefinition md = new ModelLoader().load(id, data);
			return ModelState.ok(
				ModelHash.of(md.vertexCount, md.faceCount, md.vertexX, md.vertexY, md.vertexZ,
					md.faceIndices1, md.faceIndices2, md.faceIndices3, md.faceColors),
				ModelHash.colors(md.faceCount, md.faceColors));
		} catch (Exception e) {
			return ModelState.undecodable(e.getClass().getSimpleName());
		}
	}

	@Override
	public List<Integer> modelsWithHash(String hash) {
		if (modelsByHash == null) {
			modelsByHash = new HashMap<>();
			for (Archive archive : store.getIndex(IndexType.MODELS).getArchives()) {
				int id = archive.getArchiveId();
				ModelState m = model(id);
				if (m.ok()) {
					modelsByHash.computeIfAbsent(m.hash, h -> new ArrayList<>()).add(id);
				}
			}
		}
		return modelsByHash.getOrDefault(hash, Collections.emptyList());
	}

	@Override
	public Map<Integer, int[]> kits() {
		if (kits == null) {
			kits = new HashMap<>();
			try {
				Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.IDENTKIT.getId());
				ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
				KitLoader loader = new KitLoader();
				for (FSFile f : files.getFiles()) {
					KitDefinition k = loader.load(f.getFileId(), f.getContents());
					if (k.models != null) {
						kits.put(f.getFileId(), k.models);
					}
				}
			} catch (IOException e) {
				throw new IllegalStateException("cannot read player kits from " + dir + ": " + e, e);
			}
		}
		return kits;
	}

	@Override
	public Map<Integer, NpcInfo> npcs() {
		if (npcs == null) {
			npcs = new HashMap<>();
			try {
				NpcManager manager = new NpcManager(store);
				manager.load();
				for (NpcDefinition n : manager.getNpcs()) {
					npcs.put(n.id, new NpcInfo(n.name, n.models, n.standingAnimation, n.walkingAnimation));
				}
			} catch (IOException e) {
				throw new IllegalStateException("cannot read NPC definitions from " + dir + ": " + e, e);
			}
		}
		return npcs;
	}

	@Override
	public SequenceInfo sequence(int id) {
		if (sequences == null) {
			sequences = loadSequences();
		}
		return sequences.get(id);
	}

	private Map<Integer, SequenceInfo> loadSequences() {
		Map<Integer, SequenceInfo> out = new HashMap<>();
		Map<Integer, Integer> skeletonOfFrameArchive = new HashMap<>();
		try {
			Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SEQUENCE.getId());
			SequenceLoader loader = new SequenceLoader().configureForRevision(archive.getRevision());
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			Index frames = store.getIndex(IndexType.ANIMATIONS);
			for (FSFile f : files.getFiles()) {
				SequenceDefinition s;
				try {
					s = loader.load(f.getFileId(), f.getContents());
				} catch (RuntimeException e) {
					continue;
				}
				int skeleton = -1;
				if (s.frameIDs != null && s.frameIDs.length > 0) {
					int frameArchive = s.frameIDs[0] >>> 16;
					skeleton = skeletonOfFrameArchive.computeIfAbsent(frameArchive, fa -> readSkeleton(frames, fa));
				}
				int mayaLength = s.animMayaID == -1 ? 0 : s.animMayaEnd - s.animMayaStart;
				out.put(f.getFileId(), new SequenceInfo(s.frameLengths, skeleton, s.animMayaID, mayaLength));
			}
		} catch (IOException e) {
			throw new IllegalStateException("cannot read animation sequences from " + dir + ": " + e, e);
		}
		return out;
	}

	/** A frame file starts with its skeleton (frame map) id as an unsigned short. */
	private int readSkeleton(Index frames, int frameArchive) {
		try {
			Archive archive = frames.getArchive(frameArchive);
			if (archive == null) {
				return -1;
			}
			ArchiveFiles files = archive.getFiles(storage.loadArchive(archive));
			byte[] c = files.getFiles().iterator().next().getContents();
			return ((c[0] & 0xff) << 8) | (c[1] & 0xff);
		} catch (Exception e) {
			return -1;
		}
	}
}
