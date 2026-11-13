#!/usr/bin/env python3
"""Keep Redstone Engine and Jade source overrides compilable on 1.21.1 and 1.21.11.

Checks materialized Java, not just the canonical source layer. These assertions
cover version selectors and the two Jade source implementations without loading
Minecraft/NeoForge dependencies.
"""

from __future__ import annotations

import sys
import json
import tomllib
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source


class NeoForgeCompilerCompatibility(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="bcce-neoforge-compiler-")
        cls.sources = {}
        props = load_properties()
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, props)
            downports = [root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root]
            for name in ("core/blockEntity/TileEngineRedstone_BC8.java", "compat/jade/BuildCraftJadePlugin.java",
                         "lib/crops/CropHandlerPlantable.java",
                         "transport/pipe/flow/TravellingItem.java"):
                relative = "src/main/java/buildcraft/" + name
                source = resolve_effective_source(layout, props, relative)
                assert source is not None, f"No source found for {target}: {name}"
                output = Path(cls.tmp.name) / target / relative
                _materialize_text_file(
                    source, output, logical_relative=relative,
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports),
                )
                cls.sources[target, name] = output.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def source(self, target: str, path: str) -> str:
        return self.sources[target, path]

    def test_forestry_1211_apiarists_pipe_target_isolation_and_runtime_contract(self):
        props = load_properties()
        prefix = "src/main/java/buildcraft/compat/forestry/"
        files = (
            "ForestryCompat.java", "ForestryListMatchHandler.java",
            "pipe/ForestryPipes.java", "pipe/PropolisFilterLogic.java",
            "pipe/PipeBehaviourPropolis.java", "pipe/ContainerPropolisPipe.java",
            "pipe/MessagePropolisRuleChange.java", "pipe/MessagePropolisGenomeChange.java",
            "pipe/MessagePropolisFilterState.java", "pipe/client/ForestryCompatClient.java",
            "pipe/client/GuiPropolisPipe.java",
        )
        self.assertEqual(props["target.1.21.1-neoforge.compat.forestry.enabled"], "true")
        self.assertIn("RqiWPEU4", props["target.1.21.1-neoforge.deps.forestry"])
        self.assertIn("BIogJv2D", props["target.1.21.1-neoforge.deps.patchouli"])
        modpack = json.loads((ROOT / "build-config/modpack-world-join-lock.json").read_text(encoding="utf-8"))
        self.assertEqual(modpack["targets"]["1.21.1-neoforge"]["mods"]["forestry"], "RqiWPEU4")
        self.assertEqual(modpack["targets"]["1.21.1-neoforge"]["mods"]["patchouli"], "BIogJv2D")
        gradle = (ROOT / "build-logic/loaders/neoforge-target.gradle").read_text(encoding="utf-8")
        self.assertIn("ciRuntimeProfile != 'modpack'", gradle)
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, props)
            for file in files:
                source = resolve_effective_source(layout, props, prefix + file)
                with self.subTest(target=target, file=file):
                    if target == "1.21.1-neoforge":
                        self.assertIsNotNone(source)
                        if file in {
                            "ForestryCompat.java", "pipe/MessagePropolisFilterState.java",
                            "pipe/client/ForestryCompatClient.java",
                        }:
                            self.assertIn("source-family-platforms/1.21.X/neoforge", source.as_posix())
                        else:
                            self.assertIn("version-src/1.21.1-neoforge", source.as_posix())
                    else:
                        self.assertIsNone(source)

        behaviour = (ROOT / "version-src/1.21.1-neoforge" / prefix /
                     "pipe/PipeBehaviourPropolis.java").read_text(encoding="utf-8")
        for token in ("filter.write(new CompoundTag(), TilePipeHolder.getRegistryAccess(pipe))",
                      "filter.read(nbt.getCompound(\"filter\"), TilePipeHolder.getRegistryAccess(pipe))",
                      "PipeEventItem.SideCheck", "DefaultFilterRuleType.ANYTHING",
                      "event.decreasePriority(face)", "scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR)"):
            self.assertIn(token, behaviour)
        for packet in ("RuleChange", "GenomeChange"):
            text = (ROOT / "version-src/1.21.1-neoforge" / prefix /
                    ("pipe/MessagePropolis" + packet + ".java")).read_text(encoding="utf-8")
            self.assertIn("canEdit(player, position)", text)
        menu = (ROOT / "version-src/1.21.1-neoforge" / prefix /
                "pipe/ContainerPropolisPipe.java").read_text(encoding="utf-8")
        self.assertIn("live.pipe.getHolder().canPlayerInteract(player)", menu)
        self.assertIn("filter.read(data, registries)", menu)
        compat_source = resolve_effective_source(target_layout("1.21.1-neoforge", props), props,
                                                 prefix + "ForestryCompat.java")
        compat = compat_source.read_text(encoding="utf-8")
        self.assertNotIn("event.registerBlockEntity(ForestryCapabilities.FILTER_LOGIC", compat)
        self.assertIn("MessagePropolisGenomeChange.HANDLER", compat)

        recipe = ROOT / "version-src/1.21.1-neoforge/src/main/resources/data/buildcraftcompat/recipe/pipe_item_propolis.json"
        data = json.loads(recipe.read_text(encoding="utf-8"))
        self.assertEqual(data["neoforge:conditions"],
                         [{"type": "neoforge:mod_loaded", "modid": "forestry"}])
        self.assertEqual(data["left"], {"item": "forestry:propolis"})
        self.assertEqual(data["result"], {"item": "buildcraftcompat:pipe_item_propolis", "count": 8})
        textures = ROOT / "version-src/1.21.1-neoforge/src/main/resources/assets/buildcraftcompat/textures/pipes"
        self.assertEqual(len(list(textures.glob("propolis*.png"))), 8)

    def test_forestry_1211_neoforge_metadata_scopes_optional_mod(self):
        props = load_properties()
        from source_preprocessor import preprocess_text
        path = ROOT / "source-platforms/neoforge/src/main/resources/META-INF/neoforge.mods.toml"
        text = path.read_text(encoding="utf-8")
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, props)
            processed = preprocess_text(text, minecraft=props[f"target.{target}.deps.minecraft"],
                                        family=layout.family, platform=layout.platform)
            metadata = tomllib.loads(processed)
            forestry = [dep for dep in metadata["dependencies"]["buildcraftcompat"]
                        if dep["modId"] == "forestry"]
            self.assertEqual(len(forestry), 1 if target == "1.21.1-neoforge" else 0)
            if forestry:
                self.assertEqual(forestry[0]["type"], "optional")
                self.assertIn("forestry_version_range", forestry[0]["versionRange"])

    def test_travelling_item_nbt_accessor_compatibility_across_neoforge(self):
        name = "transport/pipe/flow/TravellingItem.java"
        old = self.source("1.21.1-neoforge", name)
        # The 1.21.1 downport must not select the modern Optional facade.
        self.assertNotIn('NbtCompat.getCompound(nbt, "stack")', old)

        # 1.21.11 and all 26.X targets expose Optional-returning getters.
        # Test the effective source so preprocessing and downports cannot
        # silently bypass this compatibility contract.
        guarded = (
            'ItemStackUtil.parseOptional(registries, NbtCompat.getCompound(nbt, "stack"))',
            'NbtCompat.getByte(nbt, "colour")',
            'NbtCompat.getBoolean(nbt, "toCenter")',
            'NbtCompat.getBoolean(nbt, "pausedAtCenter")',
            'NbtCompat.getDouble(nbt, "speed")',
            'NbtCompat.getInt(nbt, "timeToDest")',
            'NbtCompat.getInt(nbt, "tickFinished")',
            'NbtCompat.getBoolean(nbt, "isPhantom")',
            'NbtCompat.getAllKeys(entries)',
        )
        for target in ("1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                java = self.source(target, name)
                for token in guarded:
                    self.assertIn(token, java)

    def test_apiarist_pipe_atlas_and_native_screen_on_1211(self):
        """Keep the world/inventory pipe textures and the ORIGINAL Forestry GUI."""
        import struct
        props = load_properties()
        atlas_path = "src/main/resources/assets/minecraft/atlases/blocks.json"
        sprites = ["buildcraftcompat:pipes/propolis" + suffix for suffix in (
            "", "_down", "_up", "_north", "_south", "_west", "_east", "_itemstack"
        )]
        for target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
                       "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                source = resolve_effective_source(target_layout(target, props), props, atlas_path)
                if source is None:
                    self.assertNotEqual(target, "1.21.1-neoforge")
                    continue
                names = [entry.get("resource") for entry in json.loads(source.read_text(encoding="utf-8"))["sources"]]
                for sprite in sprites:
                    self.assertEqual(sprite in names, target == "1.21.1-neoforge", sprite)

        assets = ROOT / "version-src/1.21.1-neoforge/src/main/resources/assets/buildcraftcompat"
        for sprite in sprites:
            png = assets / "textures" / (sprite.split(":", 1)[1] + ".png")
            data = png.read_bytes()
            self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
            self.assertEqual(struct.unpack(">II", data[16:24]), (16, 16))

        item_model = json.loads((assets / "models/item/pipe_item_propolis.json").read_text(encoding="utf-8"))
        self.assertEqual(item_model["textures"]["layer0"], "buildcraftcompat:pipes/propolis_itemstack")

        # The published Forestry CE alpha9 dependency does not expose
        # ForestryScreen/TitledForestryScreen even though the development
        # branch does. Use vanilla's shipped screen class with the exact
        # Forestry background/selector geometry, sprites and behavior.
        gui = (ROOT / "version-src/1.21.1-neoforge/src/main/java/buildcraft/compat/forestry/pipe/client/GuiPropolisPipe.java").read_text(encoding="utf-8")
        menu = (ROOT / "version-src/1.21.1-neoforge/src/main/java/buildcraft/compat/forestry/pipe/ContainerPropolisPipe.java").read_text(encoding="utf-8")
        for snippet in (
            "extends AbstractContainerScreen<ContainerPropolisPipe>",
            'ForestryConstants.forestry("textures/gui/filter.png")',
            "imageHeight = 222;", "imageWidth = 212;",
            "new PropolisRuleWidget(44, 18 + i * 18",
            "80 + j * 45 + k * 18,",
            "new PropolisSelectionWidget(0, 134)",
            "leftPos + selection.getX() + 125",
            "topPos + selection.getY() + 4",
            "setPlayerSlotsEnabled(false)",
            "selection.filterEntries",
            "selection.handleClick(localX, localY, button)",
            "if (selection.isOpen()) {",
        ):
            with self.subTest(snippet=snippet):
                self.assertIn(snippet, gui)
        for unavailable in (
            "TitledForestryScreen", "import forestry.core.platform.gui.ForestryScreen;",
            "WidgetManager", "widgetManager", "Drawable", "addHintLedger",
        ):
            with self.subTest(unavailable=unavailable):
                self.assertNotIn("import forestry.core.platform.gui." + unavailable,
                                 gui)
        self.assertNotIn("extends TitledForestryScreen", gui)
        self.assertNotIn("import forestry.core.platform.gui.", gui)
        for snippet in (
            "import net.minecraft.world.inventory.Slot;",
            "Slot from = slots.get(slot);",
            "addSlot(new SlotPropolisPipe(inventory,",
            "26 + column * 18, 140 + row * 18",
            "26 + column * 18, 198",
        ):
            self.assertIn(snippet, menu)
        self.assertNotIn("Button.builder", gui)
        self.assertNotIn("apiarist_pipe.png", gui)
        self.assertFalse((ROOT / "resource-src/1.21.X/1.21.1/assets/buildcraftcompat/textures/gui/apiarist_pipe.png").exists())
        self.assertIn("live.pipe.getHolder().canPlayerInteract(player)", menu)

    def test_apiarist_legacy_widget_parity_and_target_isolation(self):
        props = load_properties()
        prefix = "src/main/java/buildcraft/compat/forestry/pipe/"
        variants = [
            "client/widget/PropolisRuleWidget.java",
            "client/widget/PropolisSpeciesWidget.java",
            "client/widget/PropolisSelectionWidget.java",
            "client/widget/PropolisSelectionLogic.java",
            "SlotPropolisPipe.java",
        ]
        old_gui = (ROOT / "source-families/old/src/main/java/buildcraft/compat/forestry/pipe/client/GuiPropolisPipe.java").read_text(encoding="utf-8")
        new_gui = (ROOT / "version-src/1.21.1-neoforge/src/main/java/buildcraft/compat/forestry/pipe/client/GuiPropolisPipe.java").read_text(encoding="utf-8")
        # Check the *experience* against the original UI, not its unstable
        # Forestry-internal Java base class. Geometry and filtering must match.
        for anchor in (
            "imageHeight = 222;", "imageWidth = 212;",
            "44, 18 + i * 18",
            "80 + j * 45 + k * 18",
        ):
            self.assertIn(anchor, old_gui)
            self.assertIn(anchor, new_gui)
        self.assertIn("new PropolisSelectionWidget(0, 134)", new_gui)
        self.assertIn("new PropolisSelectionWidget(widgetManager, 0, 134", old_gui)

        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            layout = target_layout(target, props)
            for name in variants:
                with self.subTest(target=target, java=name):
                    source = resolve_effective_source(layout, props, prefix + name)
                    if target == "1.21.1-neoforge":
                        self.assertIsNotNone(source)
                        self.assertIn("source-family-platforms/1.21.X/neoforge", source.as_posix())
                        self.assertTrue(source.read_text(encoding="utf-8").startswith("//? source if <1.21.11"))
                    else:
                        self.assertIsNone(source)

        widget_root = ROOT / "source-family-platforms/1.21.X/neoforge" / prefix / "client/widget"
        selection_logic = (widget_root / "PropolisSelectionLogic.java").read_text(encoding="utf-8")
        selection_widget = (widget_root / "PropolisSelectionWidget.java").read_text(encoding="utf-8")
        rule_widget = (widget_root / "PropolisRuleWidget.java").read_text(encoding="utf-8")
        species_widget = (widget_root / "PropolisSpeciesWidget.java").read_text(encoding="utf-8")
        self.assertIn("SELECTABLE_PER_ROW = 11", selection_logic)
        self.assertIn("VISIBLE_ROWS = 4", selection_logic)
        self.assertIn("for (int row = 0; row < VISIBLE_ROWS; row++)", selection_logic)
        self.assertIn("getMaxScroll()", selection_logic)
        self.assertIn("scroll = Math.max(0, Math.min", selection_widget)
        self.assertIn("textures/gui/filter_selection.png", selection_widget)
        self.assertIn("textures/gui/container/creative_inventory/tabs.png", selection_widget)
        self.assertIn("IForestryClientApi.INSTANCE.getTextureManager().getSprite", rule_widget)
        self.assertIn("getDiscoveredSpecies()", species_widget)
        self.assertIn("IIndividualHandlerItem.getIndividual(carried)", species_widget)
        self.assertIn("GeneticsUtil.getIconStacks(result, type.getDefaultStage(), type)", species_widget)
        for source in (selection_logic, selection_widget, rule_widget, species_widget):
            self.assertNotIn("import forestry.core.platform.gui.", source)
            self.assertNotIn("import forestry.core.content.sorting.gui.", source)
            self.assertNotIn("WidgetManager", source)
        # The actual available dependency must be sufficient to compile the
        # screen, not just to render a matching mock-up in static tests.
        self.assertNotIn("extends TitledForestryScreen", new_gui)
        self.assertNotIn("extends ForestryScreen", new_gui)

    def test_redstone_advancement_uses_1211_profile_api(self):
        name = "core/blockEntity/TileEngineRedstone_BC8.java"
        source = self.source("1.21.1-neoforge", name)
        self.assertNotIn("import buildcraft.lib.compat.GameProfileCompat;", source)
        self.assertIn("AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT)", source)
        self.assertNotIn("GameProfileCompat.id(getOwner())", source)
        self.assertEqual(source.count("givenAdvancement = AdvancementUtil.unlockAdvancement("), 1)

    def test_redstone_advancement_uses_modern_profile_api(self):
        name = "core/blockEntity/TileEngineRedstone_BC8.java"
        source = self.source("1.21.11-neoforge", name)
        self.assertIn("import buildcraft.lib.compat.GameProfileCompat;", source)
        self.assertIn("AdvancementUtil.unlockAdvancement(GameProfileCompat.id(getOwner()), ADVANCEMENT)", source)
        self.assertNotIn("getOwner().getId()", source)
        self.assertEqual(source.count("givenAdvancement = AdvancementUtil.unlockAdvancement("), 1)

    def test_jade_fluid_names_use_vanilla_formatting_on_121(self):
        name = "compat/jade/BuildCraftJadePlugin.java"
        for target in ("1.21.1-neoforge", "1.21.11-neoforge"):
            with self.subTest(target=target):
                source = self.source(target, name)
                self.assertIn("import net.minecraft.ChatFormatting;", source)
                self.assertIn('"FluidNames")).withStyle(ChatFormatting.WHITE)', source)
                self.assertNotIn("BCTextFormat", source)

    def test_121_metadata_preprocesses_to_valid_toml(self):
        # All targets must materialize valid TOML without an optional
        # Create dependency: Create is no longer part of the build.
        metadata = ROOT / "source-platforms/neoforge/src/main/resources/META-INF/neoforge.mods.toml"
        props = load_properties()
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                materialized = Path(self.tmp.name) / target / "META-INF/neoforge.mods.toml"
                _materialize_text_file(
                    metadata, materialized,
                    logical_relative="src/main/resources/META-INF/neoforge.mods.toml",
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                )
                text = materialized.read_text(encoding="utf-8")
                parsed = tomllib.loads(text)
                self.assertIn("buildcraftrobotics", [m["modId"] for m in parsed["mods"]])
                create_deps = [d for d in parsed["dependencies"]["buildcraftcompat"]
                               if d["modId"] == "create"]
                self.assertEqual(create_deps, [])
                self.assertEqual(bool(parsed.get("mixins")), not target.startswith("1.21.1-"))

    def test_moddev_gametest_metadata_is_staged_and_cleaned(self):
        # FML 10+ discovers the dev mod from classes/java/main first. Only
        # GameTests mirror the processed mod metadata into that directory;
        # normal production JAR builds must not receive another metadata copy.
        gradle = (ROOT / 'build-logic/loaders/neoforge-target.gradle').read_text(encoding='utf-8')
        for snippet in (
            "if (gameTestRunRequested && minecraftVersion != '1.21.1')",
            "layout.buildDirectory.file('resources/main/META-INF/neoforge.mods.toml')",
            "layout.buildDirectory.file('classes/java/main/META-INF/neoforge.mods.toml')",
            "dependsOn tasks.named('compileJava'), tasks.named('processResources')",
            "tasks.register('prepareGameTestModMetadata', Copy)",
            "tasks.register('cleanGameTestModMetadata', Delete)",
            "tasks.named('runGameTestServer')",
            "dependsOn prepareGameTestModMetadata",
            "finalizedBy cleanGameTestModMetadata",
        ):
            with self.subTest(guard=snippet):
                self.assertIn(snippet, gradle)

    def test_26x_jade_retains_its_native_formatting(self):
        name = "compat/jade/BuildCraftJadePlugin.java"
        for target in ("26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                source = self.source(target, name)
                if "import buildcraft.lib.compat.minecraft.text.BCTextFormat;" in source:
                    self.assertIn("BCTextFormat.WHITE", source)
                else:
                    self.assertNotIn("BCTextFormat", source)
                    self.assertIn("ChatFormatting.WHITE", source)

    def test_plantable_crop_adapter_survives_vegetation_hierarchy_change(self):
        name = "lib/crops/CropHandlerPlantable.java"
        legacy = self.source("1.21.1-neoforge", name)
        self.assertIn("block instanceof BushBlock", legacy)
        self.assertNotIn("import net.minecraft.world.level.block.VegetationBlock;", legacy)
        for target in ("1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                current = self.source(target, name)
                self.assertIn("block instanceof VegetationBlock", current)
                self.assertNotIn("block instanceof BushBlock", current)

    def test_guide_corruption_fixture_replaces_a_frame_instead_of_adding_one(self):
        test = (ROOT / "source-platforms/neoforge/src/gametest/java/buildcraft/gametest/"
                "SystemGuideRecipeBatchGameTests.java").read_text(encoding="utf-8")
        self.assertIn("altered.writeVarInt(frameCount);", test)
        self.assertNotIn("altered.writeVarInt(frameCount + 1);", test)
        self.assertIn("i == CHUNK_SIZE / 2 ? new byte[] {0} : frame", test)
        self.assertIn("received.size() == (part == 1 ? CHUNK_SIZE - 1 : CHUNK_SIZE)", test)

    def test_263_junit_classpath_prefers_packaged_buildcraft_resources(self):
        """JUnit must read the shipped BCCE resources, not a guessed Gradle output path."""
        gradle = (ROOT / "build-logic/loaders/neoforge-target.gradle").read_text(encoding="utf-8")
        self.assertIn("if (targetName == '26.3-neoforge')", gradle)
        self.assertIn("def builtModJar = tasks.named('jar', Jar)", gradle)
        self.assertIn("dependsOn builtModJar", gradle)
        self.assertIn("classpath = files(builtModJar.flatMap { it.archiveFile }) + classpath", gradle)
        self.assertIn("def zip = new java.util.zip.ZipFile(archive)", gradle)
        self.assertIn("zip.getEntry(relative) == null", gradle)
        self.assertIn("zip.getInputStream(zip.getEntry('pack.mcmeta'))", gradle)
        self.assertNotIn("classpath = files(sourceSets.main.output.resourcesDir) + classpath", gradle)
        for relative in (
            "page_layouts.json", "original_manifest.json", "languages.json",
            "text/en_us.json", "registry/buildcraftlib.txt", "gui/icons.png",
        ):
            with self.subTest(relative=relative):
                path = ROOT / "source-shared/src/main/resources/assets/buildcraft/guide" / relative
                self.assertTrue(path.is_file(), f"Missing actual BuildCraft guide resource {path}")

    def test_263_packaging_recovers_materialized_resources_and_translations(self):
        """26.3 must package actual target resources even if ModDev loses its resource root."""
        gradle = (ROOT / "build-logic/loaders/neoforge-target.gradle").read_text(encoding="utf-8")
        marker = "// ModDevGradle 2.0.148 / Gradle 9: on 26.3 the assembled JAR"
        self.assertIn(marker, gradle)
        packaging = gradle.split(marker, 1)[1].split("tasks.named('jar', Jar) {\n    archiveClassifier", 1)[0]
        self.assertIn("if (targetName == '26.3-neoforge')", packaging)
        self.assertIn("def effectiveMainResources = new File(effectiveSourceDir, 'src/main/resources')", packaging)
        self.assertIn("tasks.named('processResources', ProcessResources)", packaging)
        self.assertIn("from(effectiveMainResources)", packaging)
        self.assertIn("from(tasks.named('processResources'))", packaging)
        self.assertIn("exclude 'pack.mcmeta', 'META-INF/neoforge.mods.toml'", packaging)
        self.assertIn("include 'pack.mcmeta', 'META-INF/neoforge.mods.toml'", packaging)
        self.assertIn("expand(resourceProperties)", packaging)
        # Variables declared with def in the separately applied common script
        # are NOT properties of the Jar task or this adapter script. Reusing them
        # here fails during Gradle configuration, before any compile/test task.
        self.assertNotIn("from(externalLocalizationsOutput)", packaging)
        self.assertNotIn("dependsOn stageBuildCraftLocalizations", packaging)
        common_gradle = (ROOT / "build-logic/common-target.gradle").read_text(encoding="utf-8")
        self.assertIn("def externalLocalizationsOutput =", common_gradle)
        self.assertIn("def stageBuildCraftLocalizations =", common_gradle)
        self.assertIn("tasks.named('processResources', ProcessResources)", common_gradle)
        self.assertIn("dependsOn stageBuildCraftLocalizations", common_gradle)
        self.assertIn("from(externalLocalizationsOutput)", common_gradle)
        self.assertEqual(2, packaging.count("duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE"))
        # The source resource templates are real and target-specific. A blank
        # directory or unexpanded metadata must never be treated as a fix.
        for relative in ("pack.mcmeta", "assets/buildcraft/guide/text/en_us.json",
                         "assets/buildcraft/guide/original_manifest.json"):
            self.assertTrue((ROOT / "source-shared/src/main/resources" / relative).is_file())

    def test_pack_metadata_respects_1_21_11_supported_formats_boundary(self):
        props = load_properties()
        source = ROOT / "source-shared/src/main/resources/pack.mcmeta"
        for target in ("1.21.1-neoforge", "1.21.11-neoforge", "26.1.2-neoforge",
                       "26.2-neoforge", "26.3-neoforge"):
            with self.subTest(target=target):
                layout = target_layout(target, props)
                output = Path(self.tmp.name) / target / "src/main/resources/pack.mcmeta"
                _materialize_text_file(
                    source, output, logical_relative="src/main/resources/pack.mcmeta",
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                )
                text = output.read_text(encoding="utf-8")
                for key, prop_key in (("pack_format", "pack.format"),
                                      ("resource_pack_format", "pack.resource_format"),
                                      ("data_pack_format", "pack.data_format")):
                    text = text.replace("${" + key + "}", props[f"target.{target}.{prop_key}"])
                pack = json.loads(text)["pack"]
                if target == "1.21.11-neoforge":
                    self.assertEqual(pack["supported_formats"], [75, 81])
                    self.assertNotIn("min_format", pack)
                    self.assertNotIn("max_format", pack)
                elif target.startswith("26."):
                    self.assertEqual(pack["min_format"], pack["pack_format"])
                    self.assertEqual(pack["max_format"], pack["pack_format"])
                    self.assertNotIn("supported_formats", pack)
                else:
                    self.assertNotIn("supported_formats", pack)
                    self.assertNotIn("min_format", pack)
                    self.assertNotIn("max_format", pack)


if __name__ == "__main__":
    unittest.main()
