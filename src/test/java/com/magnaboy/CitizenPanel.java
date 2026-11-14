package com.magnaboy;

import static com.magnaboy.Util.worldPointToShortCoord;
import com.magnaboy.serialization.CitizenInfo;
import com.magnaboy.serialization.EntityInfo;
import com.magnaboy.serialization.SceneryInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The editor's side panel. Swing EDT only: it never touches the client, the registry or an entity. It shows the
 * {@link EditorSnapshot} the client thread last sent, and hands what the contributor does to {@link CitizensDevPlugin},
 * which applies it on the client thread.
 */
class CitizenPanel extends PluginPanel {
	private final static String RELOAD_BUTTON_READY = "Reload All Entites";
	public WorldPoint wanderRegionBL;
	public WorldPoint wanderRegionTR;
	public JLabel editingTargetLabel;
	public JButton updateButton;
	public JButton deleteButton;
	public JLabel reloadWarning;
	private JLabel messageLabel;
	public JCheckBox manualFieldsToggle;
	private CitizensDevPlugin devPlugin;
	private EditorSnapshot snapshot = EditorSnapshot.EMPTY;
	/**
	 * The selected entity's stored orientation while the dropdown cannot show it; see {@link EditorOrientation}.
	 */
	private Integer customOrientation;
	/**
	 * The selected citizen's stored remarks. Kept as they are while the remarks field still shows them, because a
	 * remark may itself contain a comma.
	 */
	private String[] populatedRemarks;
	private JLabel label;
	// Editor Panel Fields
	private HashSet<JComponent> allElements;
	private JButton reloadButton;
	private JButton saveChangesButton;
	private JButton spawnButton;
	private JLabel selectedPositionLbl;
	private JTextField entityNameField;
	private JComboBox<EntityType> entityTypeSelection;
	private JComboBox<AnimationID> animIdIdleSelect;
	private JComboBox<AnimationID> animIdMoveSelect;
	private JTextField modelIdsField;
	private JTextField recolorFindField;
	private JTextField recolorReplaceField;
	private JComboBox<CardinalDirection> orientationField;
	private JTextField examineTextField;
	private JTextField remarksField;
	private JTextField scaleFieldX;
	private JTextField scaleFieldY;
	private JTextField scaleFieldZ;
	private JTextField translateFieldX;
	private JTextField translateFieldY;
	private JTextField translateFieldZ;
	private JButton selectWanderBL;
	private JButton selectWanderTR;
	private JTextField manualAnimIdIdleSelect;
	private JTextField manualAnimIdMoveSelect;

	// End Editor Fields

	public void init(CitizensDevPlugin devPlugin) {
		this.devPlugin = devPlugin;
		final JPanel layoutPanel = new JPanel();
		layoutPanel.setLayout(new GridBagLayout());
		add(layoutPanel, BorderLayout.CENTER);

		label = new JLabel();
		label.setHorizontalAlignment(SwingConstants.CENTER);

		allElements = new HashSet<>();
		addEditorComponents(layoutPanel);
		entityTypeChanged();
		apply(snapshot);
	}

	/**
	 * Shows a snapshot the client thread took, filling the form from the selected entity when the selection changed.
	 */
	public void apply(EditorSnapshot snapshot) {
		this.snapshot = snapshot;
		label.setText(snapshot.getActiveEntities() + "/" + snapshot.getTotalEntities() + " entities are active");
		if (snapshot.isSelectionChanged() && snapshot.getSelectedInfo() != null) {
			populateForm(snapshot.getSelectedInfo());
		}
		UpdateEditorFields();
	}

	private void UpdateEditorFields() {
		GameState state = snapshot.getGameState();
		WorldPoint selectedPosition = snapshot.getSelectedPosition();
		boolean hasSelection = snapshot.getSelectedInfo() != null;
		int dirtySize = snapshot.getDirtyRegions();

		reloadButton.setEnabled(state == GameState.LOGGED_IN);
		selectedPositionLbl.setText(selectedPosition == null ? "N/A" : worldPointToShortCoord(selectedPosition));

		String errorMessage = validateFields();
		boolean valid = errorMessage.isEmpty();
		boolean canSpawn = state == GameState.LOGGED_IN && valid;
		spawnButton.setEnabled(canSpawn);
		spawnButton.setText(canSpawn ? "Spawn Entity" : "Can't Spawn: " + errorMessage);

		saveChangesButton.setEnabled(true);
		saveChangesButton.setText("Save Changes");

		updateButton.setVisible(hasSelection);

		editingTargetLabel.setText("Editing: " + snapshot.getSelectedLabel());
		editingTargetLabel.setVisible(hasSelection);
		deleteButton.setVisible(hasSelection);

		// Always shown: the count covers regions that are not loaded around the player too
		reloadWarning.setText(dirtySize == 0 ? "No unsaved changes"
			: dirtySize + (dirtySize == 1 ? " region has" : " regions have") + " unsaved changes");
		reloadWarning.setForeground(dirtySize == 0 ? Color.WHITE : Color.ORANGE);
		reloadWarning.setVisible(true);
		reloadButton.setToolTipText(dirtySize == 0 ? null : "Unsaved Changes Will Be Lost");

		String message = snapshot.getMessage();
		messageLabel.setText(message == null ? "" : "<html><div style='text-align:center'>"
			+ message.replace("&", "&amp;").replace("<", "&lt;") + "</div></html>");
		messageLabel.setVisible(message != null);

		selectWanderBL.setText(wanderRegionBL == null ? "Select BL" : Util.worldPointToShortCoord(wanderRegionBL));
		selectWanderTR.setText(wanderRegionTR == null ? "Select TR" : Util.worldPointToShortCoord(wanderRegionTR));
	}

	private String validateFields() {
		if (snapshot.getSelectedPosition() == null) {
			return "No Position Selected";
		}

		EntityType selectedType = (EntityType) entityTypeSelection.getSelectedItem();

		if (fieldEmpty(entityNameField) && selectedType != EntityType.Scenery) {
			return "Empty Name";
		}

		if (fieldEmpty(modelIdsField)) {
			return "No Model IDs";
		}

		if (csvToIntArray(modelIdsField.getText()).length == 0) {
			return "Invalid Model Ids";
		}

		if (csvToIntArray(recolorFindField.getText()).length !=
			csvToIntArray(recolorReplaceField.getText()).length) {
			return "Model Color Mismatch";
		}

		if (selectedType == EntityType.WanderingCitizen) {
			if (wanderRegionBL == null || wanderRegionTR == null) {
				return "Incomplete Wander Region";
			}
		}
		return "";
	}

	private boolean fieldEmpty(JTextField f) {
		return f.getText() == null || f.getText().trim().isEmpty();
	}

	// DEV ONLY
	private void addEditorComponents(JPanel layoutPanel) {

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.BOTH;
		gbc.insets = new Insets(0, 0, 7, 2);
		gbc.weightx = 0.5;
		gbc.gridwidth = GridBagConstraints.REMAINDER;

		// Active Entities
		{
			gbc.gridx = 0;
			gbc.gridy = 0;
			layoutPanel.add(label, gbc);
		}

		// Reload Entities
		{
			gbc.gridy++;

			gbc.gridx = 0;
			reloadButton = new JButton();
			reloadButton.setText(RELOAD_BUTTON_READY);
			reloadButton.setHorizontalAlignment(SwingConstants.CENTER);
			reloadButton.setFocusable(false);

			reloadButton.addActionListener(e -> {
				devPlugin.reloadEntities();
			});
			layoutPanel.add(reloadButton, gbc);

			gbc.gridy++;
			reloadWarning = new JLabel("Unsaved Changes Will Be Lost");
			reloadWarning.setFont(FontManager.getRunescapeSmallFont());
			reloadWarning.setBorder(new EmptyBorder(0, 0, 0, 0));
			reloadWarning.setForeground(Color.ORANGE);
			reloadWarning.setVerticalAlignment(SwingConstants.NORTH);
			reloadWarning.setHorizontalAlignment(SwingConstants.CENTER);
			reloadWarning.setVisible(false);
			layoutPanel.add(reloadWarning, gbc);

			gbc.gridy++;
			messageLabel = new JLabel();
			messageLabel.setFont(FontManager.getRunescapeSmallFont());
			messageLabel.setHorizontalAlignment(SwingConstants.CENTER);
			messageLabel.setVisible(false);
			layoutPanel.add(messageLabel, gbc);

		}

		// Editing Target
		{
			gbc.gridy++;
			editingTargetLabel = new JLabel();
			editingTargetLabel.setHorizontalAlignment(SwingConstants.CENTER);
			editingTargetLabel.setForeground(Color.orange);
			editingTargetLabel.setVisible(false);
			layoutPanel.add(editingTargetLabel, gbc);
		}

		// Selected Position Label
		{
			gbc.gridy++;

			gbc.gridx = 0;
			selectedPositionLbl = createLabeledComponent(new JLabel(), "Selected Position", layoutPanel, gbc);
		}

		// Name Field
		{
			gbc.gridy++;

			gbc.gridx = 0;
			entityNameField = createLabeledComponent(new JTextField(), "Entity Name", layoutPanel, gbc);
		}

		// Examine Text
		{
			gbc.gridy++;

			gbc.gridx = 0;
			examineTextField = createLabeledComponent(new JTextField(), "Examine Text", layoutPanel, gbc);
			examineTextField.setText("A Citizen of Gielinor");
		}

		// Entity Type
		{
			gbc.gridy++;

			gbc.gridx = 0;

			entityTypeSelection = createLabeledComponent(new JComboBox<>(EntityType.values()), "Entity Type", layoutPanel, gbc);
			entityTypeSelection.setFocusable(false);
			entityTypeSelection.addActionListener(e -> {
				entityTypeChanged();
			});
		}

		// Cardinal Direction
		{
			gbc.gridy++;

			gbc.gridx = 0;
			orientationField = createLabeledComponent(new JComboBox<>(CardinalDirection.values()), "Base Orientation", layoutPanel, gbc);
			orientationField.setSelectedItem(CardinalDirection.South);
			orientationField.setFocusable(false);
			ListCellRenderer<? super CardinalDirection> directionRenderer = orientationField.getRenderer();
			orientationField.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
				Component cell = directionRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				// Nothing selected: the selected entity's orientation is not one of the directions
				if (value == null && customOrientation != null && cell instanceof JLabel) {
					((JLabel) cell).setText("Custom (" + customOrientation + ")");
				}
				return cell;
			});
		}

		// Animations
		{
			gbc.gridy++;
			gbc.gridwidth = 2;

			gbc.insets = new Insets(15, 0, 0, 2);
			manualFieldsToggle = new JCheckBox("Manual Animation IDs");
			manualFieldsToggle.setFont(FontManager.getRunescapeSmallFont());
			manualFieldsToggle.setHorizontalAlignment(SwingConstants.RIGHT);
			layoutPanel.add(manualFieldsToggle, gbc);

			manualFieldsToggle.addItemListener(e -> {
				if (e.getStateChange() == ItemEvent.SELECTED || e.getStateChange() == ItemEvent.DESELECTED) {
					boolean checked = manualFieldsToggle.isSelected();

					animIdIdleSelect.getParent().setVisible(!checked);
					if (entityTypeSelection.getSelectedItem() != EntityType.Scenery) {
						animIdMoveSelect.getParent().setVisible(!checked);
					}

					manualAnimIdIdleSelect.getParent().setVisible(checked);
					if (entityTypeSelection.getSelectedItem() != EntityType.Scenery) {
						manualAnimIdMoveSelect.getParent().setVisible(checked);
					}
				}
			});

			AnimationID[] animIds = AnimationID.values();
			Arrays.sort(animIds, Comparator.comparing(Enum::name));

			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			gbc.insets = new Insets(0, 0, 7, 2);
			animIdIdleSelect = createLabeledComponent(new JComboBox<>(animIds), "Idle Animation", layoutPanel, gbc);
			animIdIdleSelect.setSelectedItem(AnimationID.HumanIdle);
			animIdIdleSelect.setFocusable(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			animIdMoveSelect = createLabeledComponent(new JComboBox<>(animIds), "Move Animation", layoutPanel, gbc);
			animIdMoveSelect.setSelectedItem(AnimationID.HumanWalk);
			animIdMoveSelect.setFocusable(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			manualAnimIdIdleSelect = createLabeledComponent(new JTextField(), "Idle Animation", layoutPanel, gbc);
			manualAnimIdIdleSelect.setText("Not Yet Implemented");
			manualAnimIdIdleSelect.getParent().setVisible(false);

			gbc.gridy++;
			gbc.gridwidth = 2;
			manualAnimIdMoveSelect = createLabeledComponent(new JTextField(), "Move Animation", layoutPanel, gbc);
			manualAnimIdMoveSelect.setText("Not Yet Implemented");
			manualAnimIdMoveSelect.getParent().setVisible(false);
		}

		// Models
		{
			gbc.gridy++;
			gbc.gridx = 0;
			modelIdsField = createLabeledComponent(new JTextField(), "Model Ids", layoutPanel, gbc);
			modelIdsField.setToolTipText("Integers only, separated by commas");
		}

		// Remarks
		{
			gbc.gridy++;
			gbc.gridx = 0;
			gbc.gridwidth = 2;
			remarksField = createLabeledComponent(new JTextField(), "Remarks", layoutPanel, gbc);
			remarksField.setToolTipText("Phrases, separated by commas");
		}

		// Model Recolors
		{
			gbc.gridy++;
			gbc.gridwidth = 1;
			recolorFindField = createLabeledComponent(new JTextField(), "Find Model Colors", layoutPanel, gbc);
			recolorFindField.setToolTipText("Integers only, separated by commas");

			gbc.gridx = 1;
			recolorReplaceField = createLabeledComponent(new JTextField(), "Replace Model Colors", layoutPanel, gbc);
			recolorReplaceField.setToolTipText("Integers only, separated by commas");
		}

		// Scale
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;
			scaleFieldX = new JTextField();
			scaleFieldY = new JTextField();
			scaleFieldZ = new JTextField();
			createLabeledMultiComponent("Scale", layoutPanel, gbc, scaleFieldX, scaleFieldY, scaleFieldZ);
		}

		// Translation
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;
			translateFieldX = new JTextField();
			translateFieldY = new JTextField();
			translateFieldZ = new JTextField();
			createLabeledMultiComponent("Translation", layoutPanel, gbc, translateFieldX, translateFieldY, translateFieldZ);
		}

		// Wander Region
		{
			gbc.gridy++;
			gbc.gridwidth = 2;
			gbc.gridx = 0;

			selectWanderBL = new JButton();
			selectWanderBL.setText("Select BL");
			selectWanderBL.setFocusable(false);
			selectWanderBL.addActionListener(e -> {
				wanderRegionBL = snapshot.getSelectedPosition();
				UpdateEditorFields();
			});

			selectWanderTR = new JButton();
			selectWanderTR.setText("Select TR");
			selectWanderTR.setFocusable(false);
			selectWanderTR.addActionListener(e -> {
				wanderRegionTR = snapshot.getSelectedPosition();
				UpdateEditorFields();
			});

			createLabeledMultiComponent("Wander Region", layoutPanel, gbc, selectWanderBL, selectWanderTR);
		}

		// Spawn/Save Button
		{
			gbc.gridy++;
			gbc.gridx = 0;
			spawnButton = new JButton();
			spawnButton.setText("Spawn Entity");
			spawnButton.setFocusable(false);
			spawnButton.addActionListener(e -> {
				WorldPoint position = snapshot.getSelectedPosition();
				if (position == null) {
					return;
				}
				if (entityTypeSelection.getSelectedItem() == EntityType.Scenery) {
					devPlugin.spawnEntity(buildSceneryInfo(position.getRegionID(), position));
				} else {
					devPlugin.spawnEntity(buildCitizenInfo(position.getRegionID(), position));
				}
			});
			layoutPanel.add(spawnButton, gbc);

			gbc.gridy++;
			gbc.gridx = 0;
			updateButton = new JButton();
			updateButton.setText("Update Entity");
			updateButton.setFocusable(false);
			updateButton.addActionListener(e -> {
				EntityInfo selected = snapshot.getSelectedInfo();
				if (selected == null) {
					return;
				}
				// Without a selected tile the entity stays where it is
				WorldPoint position = snapshot.getSelectedPosition();
				WorldPoint location = position == null ? selected.worldLocation : position;
				EntityInfo info;
				if (selected instanceof CitizenInfo) {
					info = buildCitizenInfo(selected.regionId, location);
				} else {
					info = buildSceneryInfo(selected.regionId, location);
				}
				info.uuid = selected.uuid;
				devPlugin.updateEntity(info);
			});
			layoutPanel.add(updateButton, gbc);
		}

		// Delete Button
		{
			gbc.gridy++;
			gbc.gridx = 0;
			deleteButton = new JButton();
			deleteButton.setText("Delete Entity");
			deleteButton.setFocusable(false);
			deleteButton.setVisible(false);
			deleteButton.setBackground(new Color(135, 58, 58));
			deleteButton.addActionListener(e -> {
				EntityInfo selected = snapshot.getSelectedInfo();
				if (selected != null) {
					devPlugin.deleteEntity(selected.regionId, selected.uuid);
				}
			});
			layoutPanel.add(deleteButton, gbc);
		}

		// Save Changes
		{
			gbc.gridy++;

			saveChangesButton = new JButton();
			saveChangesButton.setText("Save Changes");
			saveChangesButton.setFocusable(false);
			saveChangesButton.addActionListener(e -> {
				devPlugin.saveChanges();
			});

			gbc.gridx = 0;
			layoutPanel.add(saveChangesButton, gbc);
		}
	}

	private int[] csvToIntArray(String csv) {
		String[] separated = csv.split(",", -1);
		int[] validInts = new int[separated.length];
		for (int i = 0; i < validInts.length; i++) {
			try {
				validInts[i] = Integer.parseInt(separated[i].trim());
			} catch (Exception e) {
				return new int[0];
			}
		}
		return validInts;
	}

	private float parseOrDefault(Object o, float defaultResult) {
		float result = defaultResult;
		String s = "";
		if (o instanceof JTextField) {
			s = ((JTextField) o).getText();
		}
		if (o instanceof String) {
			s = (String) o;
		}
		try {
			result = Float.parseFloat(s);
		} catch (Exception ignored) {
		}

		return result;
	}

	private CitizenInfo buildCitizenInfo(int regionId, WorldPoint location) {
		CitizenInfo info = new CitizenInfo();
		info.uuid = UUID.randomUUID();
		info.regionId = regionId;
		info.name = entityNameField.getText();
		info.examineText = examineTextField.getText();
		info.worldLocation = location;
		info.entityType = (EntityType) entityTypeSelection.getSelectedItem();
		info.idleAnimation = (AnimationID) animIdIdleSelect.getSelectedItem();
		info.moveAnimation = (AnimationID) animIdMoveSelect.getSelectedItem();
		info.modelIds = csvToIntArray(modelIdsField.getText());
		info.modelRecolorFind = csvToIntArray(recolorFindField.getText());
		info.modelRecolorReplace = csvToIntArray(recolorReplaceField.getText());
		info.baseOrientation = selectedOrientation();
		if (populatedRemarks != null && remarksField.getText().equals(String.join(",", populatedRemarks))) {
			info.remarks = populatedRemarks.clone();
		} else {
			info.remarks = remarksField.getText().length() > 0 ? remarksField.getText().split(",", -1) : null;
		}

		if (fieldEmpty(scaleFieldX) && fieldEmpty(scaleFieldY) && fieldEmpty(scaleFieldZ)) {
			info.scale = null;
		} else {
			info.scale = new float[]{
				parseOrDefault(scaleFieldX, 1),
				parseOrDefault(scaleFieldY, 1),
				parseOrDefault(scaleFieldZ, 1),
			};
		}
		if (fieldEmpty(translateFieldX) && fieldEmpty(translateFieldY) && fieldEmpty(translateFieldZ)) {
			info.translate = null;
		} else {
			info.translate = new float[]{
				parseOrDefault(translateFieldX, 0),
				parseOrDefault(translateFieldY, 0),
				parseOrDefault(translateFieldZ, 0),
			};
		}

		if (info.entityType == EntityType.WanderingCitizen) {
			info.wanderBoxTR = wanderRegionTR;
			info.wanderBoxBL = wanderRegionBL;
		}

		return info;
	}

	private SceneryInfo buildSceneryInfo(int regionId, WorldPoint location) {
		SceneryInfo info = new SceneryInfo();
		info.uuid = UUID.randomUUID();
		info.regionId = regionId;
		info.entityType = EntityType.Scenery;
		info.worldLocation = location;
		info.modelIds = csvToIntArray(modelIdsField.getText());
		info.modelRecolorFind = csvToIntArray(recolorFindField.getText());
		info.modelRecolorReplace = csvToIntArray(recolorReplaceField.getText());
		info.baseOrientation = selectedOrientation();
		info.idleAnimation = (AnimationID) animIdIdleSelect.getSelectedItem();

		if (fieldEmpty(scaleFieldX) && fieldEmpty(scaleFieldY) && fieldEmpty(scaleFieldZ)) {
			info.scale = null;
		} else {
			info.scale = new float[]{
				parseOrDefault(scaleFieldX, 1),
				parseOrDefault(scaleFieldY, 1),
				parseOrDefault(scaleFieldZ, 1),
			};
		}

		if (fieldEmpty(translateFieldX) && fieldEmpty(translateFieldY) && fieldEmpty(translateFieldZ)) {
			info.translate = null;
		} else {
			info.translate = new float[]{
				parseOrDefault(translateFieldX, 0),
				parseOrDefault(translateFieldY, 0),
				parseOrDefault(translateFieldZ, 0),
			};
		}
		return info;
	}

	private int selectedOrientation() {
		return EditorOrientation.fromDropdown((CardinalDirection) orientationField.getSelectedItem(), customOrientation);
	}

	private <T extends JComponent> T createLabeledComponent(T component, String label, JPanel panel, GridBagConstraints constraints) {
		final JPanel container = new JPanel();
		container.setLayout(new BorderLayout());

		final JLabel uiLabel = new JLabel(label);

		uiLabel.setFont(FontManager.getRunescapeSmallFont());
		uiLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
		uiLabel.setForeground(Color.WHITE);

		container.add(uiLabel, BorderLayout.NORTH);
		container.add(component, BorderLayout.CENTER);

		panel.add(container, constraints);
		allElements.add(container);
		allElements.add(component);
		return component;
	}

	//Creates multiple components under a single label
	private void createLabeledMultiComponent(String label, JPanel panel, GridBagConstraints constraints, JComponent... comps) {
		final JPanel container = new JPanel();
		container.setLayout(new GridBagLayout());

		GridBagConstraints containerGbc = new GridBagConstraints();
		containerGbc.fill = GridBagConstraints.HORIZONTAL;
		containerGbc.insets = new Insets(0, 0, 0, 2);
		containerGbc.weightx = 0.5;
		containerGbc.gridwidth = comps.length;
		final JLabel uiLabel = new JLabel(label);

		uiLabel.setFont(FontManager.getRunescapeSmallFont());
		uiLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
		uiLabel.setForeground(Color.WHITE);

		containerGbc.gridx = 0;
		container.add(uiLabel, containerGbc);
		containerGbc.weightx = 0.5f;
		containerGbc.gridwidth = 1;
		int i = 0;
		for (JComponent comp : comps) {
			containerGbc.gridx = i++;
			container.add(comp, containerGbc);
			allElements.add(comp);
		}
		panel.add(container, constraints);
	}

	private void entityTypeChanged() {
		for (JComponent jc : allElements) {
			jc.setVisible(true);
			jc.getParent().setVisible(true);
		}

		EntityType type = (EntityType) entityTypeSelection.getSelectedItem();

		boolean checked = manualFieldsToggle.isSelected();
		// Turn off irrelevant components
		switch (type) {
			// We get the parents because they are each in individual containers with their labels
			case StationaryCitizen:
			case ScriptedCitizen:
				selectWanderTR.getParent().setVisible(false);
				selectWanderBL.getParent().setVisible(false);

				animIdIdleSelect.getParent().setVisible(!checked);
				animIdMoveSelect.getParent().setVisible(!checked);
				manualAnimIdIdleSelect.getParent().setVisible(checked);
				manualAnimIdMoveSelect.getParent().setVisible(checked);
				break;

			case Scenery:
				entityNameField.getParent().setVisible(false);
				examineTextField.getParent().setVisible(false);
				manualAnimIdMoveSelect.getParent().setVisible(false);
				animIdIdleSelect.getParent().setVisible(!checked);
				manualAnimIdIdleSelect.getParent().setVisible(checked);
				remarksField.getParent().setVisible(false);
				selectWanderTR.getParent().setVisible(false);
				selectWanderBL.getParent().setVisible(false);
				break;
		}

		if (type == EntityType.StationaryCitizen || type == EntityType.Scenery) {
			animIdMoveSelect.getParent().setVisible(false);
		}
	}

	/**
	 * Fills the form from the selected entity's stored roster entry.
	 */
	private void populateForm(EntityInfo info) {
		entityTypeSelection.setSelectedItem(info instanceof SceneryInfo ? EntityType.Scenery : info.entityType);
		customOrientation = EditorOrientation.customValue(info.baseOrientation);
		orientationField.setSelectedItem(EditorOrientation.toDropdown(info.baseOrientation));
		orientationField.repaint();
		animIdIdleSelect.setSelectedItem(info.idleAnimation);
		modelIdsField.setText(toCsv(info.modelIds));
		recolorFindField.setText(toCsv(info.modelRecolorFind));
		recolorReplaceField.setText(toCsv(info.modelRecolorReplace));

		translateFieldX.setText(info.translate == null ? "" : String.valueOf(info.translate[0]));
		translateFieldY.setText(info.translate == null ? "" : String.valueOf(info.translate[1]));
		translateFieldZ.setText(info.translate == null ? "" : String.valueOf(info.translate[2]));

		scaleFieldX.setText(info.scale == null ? "" : String.valueOf(info.scale[0]));
		scaleFieldY.setText(info.scale == null ? "" : String.valueOf(info.scale[1]));
		scaleFieldZ.setText(info.scale == null ? "" : String.valueOf(info.scale[2]));

		populatedRemarks = null;
		if (info instanceof CitizenInfo) {
			CitizenInfo c = (CitizenInfo) info;
			entityNameField.setText(c.name);
			examineTextField.setText(c.examineText);
			animIdMoveSelect.setSelectedItem(c.moveAnimation);
			populatedRemarks = c.remarks;
			remarksField.setText(c.remarks == null ? "" : String.join(",", c.remarks));

			if (c.entityType == EntityType.WanderingCitizen) {
				wanderRegionBL = c.wanderBoxBL;
				wanderRegionTR = c.wanderBoxTR;
			}
		}
	}

	private static String toCsv(int[] values) {
		return values == null ? "" : Util.intArrayToString(values);
	}
}
