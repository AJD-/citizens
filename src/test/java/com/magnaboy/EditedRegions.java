package com.magnaboy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;

/**
 * The regions the contributor editor changed this session. Client thread only.
 * <p>
 * For each edited region it keeps the editor's own version: a {@link CitizenRegion} with roster lists of its own, so
 * nothing the registry does to its regions (unloading one, or {@code cleanUp} emptying the rosters) can change or lose
 * an edit. The roster entries themselves are shared with the registry; neither side changes an entry in place, edits
 * replace them. Only {@link #discardUnsaved()} (the editor's "Reload All Entities") throws edits away.
 * <p>
 * The editor's version is the one source of truth for its region for the rest of the session: whenever the registry
 * holds a different region object for that id (it was loaded again from the bundled data), {@link #installInto}
 * puts the editor's version back before anything else is shown or edited.
 */
@Slf4j
final class EditedRegions {
	/**
	 * The editor's version of every region edited this session, by id.
	 */
	private final Map<Integer, CitizenRegion> current = new HashMap<>();
	/**
	 * The version last written to disk this session, by id.
	 */
	private final Map<Integer, CitizenRegion> saved = new HashMap<>();
	private final Set<Integer> unsaved = new TreeSet<>();
	/**
	 * The registry's region object that holds the editor's version, by id.
	 */
	private final Map<Integer, CitizenRegion> installed = new HashMap<>();

	/**
	 * Records {@code edited}, the registry's region just changed by the editor, as the editor's version with unsaved
	 * changes.
	 */
	void recordEdit(CitizenRegion edited) {
		current.put(edited.regionId, copyOf(edited));
		installed.put(edited.regionId, edited);
		unsaved.add(edited.regionId);
	}

	/**
	 * The ids of every region edited this session, saved or not.
	 */
	List<Integer> regionIds() {
		return new ArrayList<>(current.keySet());
	}

	int unsavedCount() {
		return unsaved.size();
	}

	/**
	 * The editor's version of a region, or null if it was not edited this session.
	 */
	CitizenRegion versionOf(int regionId) {
		return current.get(regionId);
	}

	/**
	 * Makes the registry's loaded {@code region} hold the editor's version of it, unless it already does or the region
	 * was not edited.
	 *
	 * @return whether the registry changed
	 */
	boolean installInto(CitizenRegistry registry, CitizenRegion region) {
		CitizenRegion version = current.get(region.regionId);
		if (version == null || installed.get(region.regionId) == region) {
			return false;
		}
		registry.replaceRegionContents(region.regionId, version.citizenRoster, version.sceneryRoster);
		installed.put(region.regionId, region);
		return true;
	}

	/**
	 * Writes every region with unsaved changes. A region that cannot be written stays unsaved and is logged; the others
	 * are still written.
	 *
	 * @return the ids that could not be written
	 */
	List<Integer> saveAll(RegionFileWriter writer) {
		List<Integer> failed = new ArrayList<>();
		for (Integer regionId : new ArrayList<>(unsaved)) {
			CitizenRegion version = current.get(regionId);
			try {
				writer.write(version);
				saved.put(regionId, version);
				unsaved.remove(regionId);
			} catch (IOException | RuntimeException e) {
				log.warn("Could not save region {}", regionId, e);
				failed.add(regionId);
			}
		}
		return failed;
	}

	/**
	 * Throws away the unsaved changes: a region saved this session goes back to its saved version, any other one is
	 * forgotten. The registry must then be reloaded; every version is installed again.
	 */
	void discardUnsaved() {
		for (Integer regionId : unsaved) {
			CitizenRegion savedVersion = saved.get(regionId);
			if (savedVersion == null) {
				current.remove(regionId);
			} else {
				current.put(regionId, savedVersion);
			}
		}
		unsaved.clear();
		installed.clear();
	}

	private static CitizenRegion copyOf(CitizenRegion region) {
		CitizenRegion copy = new CitizenRegion();
		copy.version = region.version;
		copy.regionId = region.regionId;
		copy.citizenRoster = new ArrayList<>(region.citizenRoster);
		copy.sceneryRoster = new ArrayList<>(region.sceneryRoster);
		return copy;
	}
}
