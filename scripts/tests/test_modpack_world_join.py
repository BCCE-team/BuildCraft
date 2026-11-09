"""Checks IT16's deployment lock, process probes and fail-closed behavior offline."""
import importlib.util
import json
import hashlib
import tempfile
import socket
import struct
import unittest
from unittest import mock
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ci-modpack-world-join.py"
spec = importlib.util.spec_from_file_location("bcce_world_join_probe", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ModpackWorldJoinTest(unittest.TestCase):
    def test_pinned_versions_select_correct_loader_and_generation(self):
        old_generation, old = probe.resolve_locked_mods("1.20.1-forge")
        new_generation, new = probe.resolve_locked_mods("1.21.1-neoforge")
        self.assertEqual(old_generation, "old")
        self.assertEqual(new_generation, "1.21.X")
        self.assertEqual(set(old), {"ae2", "modernfix", "guideme"})
        self.assertEqual(set(new), {"ae2", "modernfix", "forestry", "guideme", "patchouli"})
        self.assertTrue(all(old.values()))
        self.assertTrue(all(new.values()))

    def test_unsupported_modpack_target_fails_closed(self):
        with self.assertRaises(ValueError):
            probe.resolve_locked_mods("26.4-neoforge")

    def test_startup_probe_does_not_confuse_atlas_with_world_entry(self):
        startup = "Created: minecraft:textures/atlas/blocks.png-atlas"
        joined = "[Server thread/INFO] [minecraft/MinecraftServer]: Dev joined the game"
        self.assertFalse(probe.READY_RE.search(startup))
        self.assertFalse(probe.JOIN_RE.search(startup))
        self.assertTrue(probe.JOIN_RE.search(joined))

    def test_rcon_packet_has_a_complete_binary_frame(self):
        packet = probe.rcon_packet(42, probe.RCON_EXEC, "list")
        size = struct.unpack("<i", packet[:4])[0]
        self.assertEqual(size, len(packet) - 4)
        self.assertEqual(struct.unpack("<ii", packet[4:12]), (42, probe.RCON_EXEC))
        self.assertTrue(packet.endswith(b"list\0\0"))

    def test_execute_condition_has_explicit_feedback_instead_of_say_token(self):
        for reply in ("Test passed", "Test passed, count: 1", "Test passed, count: 3"):
            with self.subTest(reply=reply):
                self.assertTrue(probe.execute_test_result(reply))
        for reply in ("Test failed", "Test failed, count: 0"):
            with self.subTest(reply=reply):
                self.assertFalse(probe.execute_test_result(reply))
        for reply in ("", "BCCE_IT16_BLOCK_VERIFIED", "[Server] BCCE_IT16_BLOCK_VERIFIED",
                      "Test passed, count: 0", "Test failed, count: 1", "Some Test passed",
                      "Unknown command", "Test passed, count: nope"):
            with self.subTest(reply=reply), self.assertRaises(RuntimeError):
                probe.execute_test_result(reply)

    def test_it16_never_uses_rcon_say_to_verify_a_predicate(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("run say", source)
        self.assertIn("execute if block {position} {block_id}", source)
        self.assertIn("execute_test_result(checked)", source)

    def test_rcon_rejects_truncated_frames(self):
        reader, writer = socket.socketpair()
        try:
            writer.sendall(struct.pack("<i", 30) + b"short")
            writer.shutdown(socket.SHUT_WR)
            with self.assertRaises(ConnectionError):
                probe.recv_rcon(reader)
        finally:
            writer.close()
            reader.close()

    def test_live_nbt_position_parser_supports_negative_coordinates(self):
        self.assertEqual(probe.parse_entity_position(
            "Steve has the following entity data: [-12.75d, 65.0d, 6.999d]"),
            (-13, 65, 6))
        with self.assertRaises(RuntimeError):
            probe.parse_entity_position("minecraft block atlas rendered")

    def test_join_player_name_is_required_for_in_world_probes(self):
        self.assertEqual(probe.player_name_from_join_log(
            "[Server thread/INFO]: Dev_Test joined the game"), "Dev_Test")
        with self.assertRaises(RuntimeError):
            probe.player_name_from_join_log("Created atlas blocks.png-atlas")

    def test_actual_world_probe_places_real_modded_blocks_and_rechecks_on_rejoin(self):
        executed = []
        def fake_command(port, password, command):
            executed.append(command)
            if command == "list":
                return "There are 1 of a max of 2 players online: Dev_Test"
            if command == "data get entity Dev_Test Pos":
                return "Dev_Test has the following entity data: [-12.5d, 64.0d, 8.5d]"
            if command.startswith("setblock "):
                return "Changed the block at -10, 64, 10"
            if command.startswith("execute if block "):
                return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at -10, 64, 10 has the following block data: {id: 'buildcraftfactory:entity_tank'}"
            raise AssertionError("Unexpected RCON command: " + command)
        with mock.patch.object(probe, "rcon_command", side_effect=fake_command):
            origin = probe.verify_actual_modded_world(25589, "secret", "Dev_Test", origin=None)
            self.assertEqual(origin, (-13, 64, 8))
            self.assertEqual(sum(x.startswith("setblock ") for x in executed), 8)
            self.assertEqual(sum("minecraft:stone" in x for x in executed), 4)
            self.assertIn("setblock -11 63 10 minecraft:stone", executed)
            self.assertIn("setblock -11 64 10 buildcraftfactory:tank", executed)
            self.assertIn("setblock -9 64 10 ae2:controller", executed)
            self.assertIn("setblock -7 64 10 create:shaft", executed)
            self.assertIn("setblock -5 64 10 buildcraftfactory:autoworkbench_item", executed)
            executed.clear()
            self.assertEqual(probe.verify_actual_modded_world(25589, "secret", "Dev_Test", origin=origin), origin)
            self.assertEqual(sum(x.startswith("setblock ") for x in executed), 0)
            self.assertEqual(sum(x.startswith("execute if block ") for x in executed), 4)
            self.assertTrue(all(" run say " not in x for x in executed))

    def test_block_without_block_entity_still_passes_actual_world_probe(self):
        def fake_command(port, password, command):
            if command == "list":
                return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("execute if block "):
                return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            raise AssertionError(f"unexpected command: {command}")
        with mock.patch.object(probe, "rcon_command", side_effect=fake_command):
            self.assertEqual(probe.verify_actual_modded_world(25589, "secret", "Dev_Test", origin=(0, 64, 0)),
                             (0, 64, 0))

    def test_actual_world_probe_rejects_missing_modded_block(self):
        def fake_command(port, password, command):
            if command == "list":
                return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("execute if block "):
                return "Test failed"
            return "The block at 0, 64, 0 has the following block data: {id: 'dummy'}"
        with mock.patch.object(probe, "rcon_command", side_effect=fake_command):
            with self.assertRaisesRegex(RuntimeError, "Block registry/world check failed"):
                probe.verify_actual_modded_world(25589, "secret", "Dev_Test", origin=(0, 64, 0))

    def test_faults_are_detected_and_no_partially_valid_join_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("Crash report saved to: /tmp/minecraft/crash-1.txt\n", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                probe.check_fatal(log)


    def test_live_reload_datapack_format_matches_target_registry(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = Path(tmp)
            old = probe.install_live_recipe_fixture("1.20.1-forge", world / "old")
            new = probe.install_live_recipe_fixture("1.21.1-neoforge", world / "modern")
            self.assertIn("/recipes/", old.as_posix())
            self.assertIn("/recipe/", new.as_posix())
            import json
            self.assertEqual(json.loads(old.read_text())["result"], {"item": "minecraft:stone", "count": 1})
            self.assertEqual(json.loads(new.read_text())["result"], {"id": "minecraft:stone", "count": 1})
            self.assertEqual(json.loads((old.parents[3] / "pack.mcmeta").read_text())["pack"]["pack_format"], 15)
            self.assertEqual(json.loads((new.parents[3] / "pack.mcmeta").read_text())["pack"]["pack_format"], 48)
            with self.assertRaises(ValueError):
                probe.recipe_pack_layout("26.3-neoforge")

    def test_recipe_rcon_reply_must_be_recognized_not_assumed(self):
        self.assertEqual(probe.recipe_reply_state("Gave 1 recipe to Dev_Test"), "present")
        self.assertEqual(probe.recipe_reply_state("Unlocked 1 recipes for Dev"), "present")
        self.assertEqual(probe.recipe_reply_state("Unlocked 1 recipe for Dev"), "present")
        self.assertEqual(probe.recipe_reply_state("No new recipes were given to Dev_Test"), "present")
        self.assertEqual(probe.recipe_reply_state("Unknown recipe 'bcce_ci:reload_probe'"), "absent")
        with self.assertRaises(RuntimeError):
            probe.recipe_reply_state("Reloading ResourceManager")
        with self.assertRaises(RuntimeError):
            probe.recipe_reply_state("Unlocked 0 recipes for Dev")

    def test_live_recipe_reload_is_real_state_cycle_and_client_stays_online(self):
        with tempfile.TemporaryDirectory() as tmp:
            state = {"loaded": False, "online": True}
            events = []
            def fake_rcon(_port, _password, cmd):
                events.append(cmd)
                if cmd.startswith("datapack enable"):
                    state["loaded"] = True
                    return "Enabled data pack"
                if cmd == "datapack list available": return "Available: [file/bcce-it09-live-recipes]"
                if cmd == "datapack list enabled": return "Enabled: [vanilla]"
                if cmd == "reload":
                    return "Reloading!"
                if cmd.startswith("recipe give"):
                    exists = (Path(tmp) / "bcce-it16-world/datapacks/bcce-it09-live-recipes"
                              "/data/bcce_ci/recipes/reload_probe.json").exists()
                    return "Gave 1 recipe to Dev_Test" if exists else "Unknown recipe"
                if cmd == "list":
                    return "There are 1 of a max of 2 players online: Dev_Test" if state["online"] else "There are 0 players online:"
                raise AssertionError(cmd)
            with mock.patch.object(probe, "rcon_command", side_effect=fake_rcon):
                class Alive:
                    def poll(self): return None
                probe.verify_live_recipe_reload("1.20.1-forge", Path(tmp), 25589, "secret", "Dev_Test", [Alive()])
            self.assertEqual(sum(cmd == "reload" for cmd in events), 3)
            self.assertEqual(sum(cmd.startswith("recipe give") for cmd in events), 3)
            self.assertEqual(events[-1], "list")

    def test_broken_datapack_reload_restores_test_fixture_on_disk(self):
        with tempfile.TemporaryDirectory() as tmp:
            expected = Path(tmp) / "bcce-it16-world/datapacks/bcce-it09-live-recipes"
            counter = [0]
            def fake_rcon(_port, _password, cmd):
                if cmd.startswith("datapack enable"): return "Enabled data pack"
                if cmd == "datapack list available": return "Available: [file/bcce-it09-live-recipes]"
                if cmd == "datapack list enabled": return "Enabled: [vanilla]"
                if cmd == "reload":
                    counter[0] += 1
                    return "Reloading!" if counter[0] == 1 else "Unknown command"
                if cmd.startswith("recipe give"): return "Gave 1 recipe to Dev_Test"
                raise AssertionError(cmd)
            with mock.patch.object(probe, "rcon_command", side_effect=fake_rcon):
                with self.assertRaisesRegex(RuntimeError, "removal reload rejected"):
                    probe.verify_live_recipe_reload("1.20.1-forge", Path(tmp), 25589, "secret", "Dev_Test", [])
            self.assertTrue((expected / "data/bcce_ci/recipes/reload_probe.json").exists())

    def test_reload_does_not_pass_if_client_disconnected(self):
        with tempfile.TemporaryDirectory() as tmp:
            def fake_rcon(_port, _password, cmd):
                if cmd.startswith("datapack enable"): return "Enabled data pack"
                if cmd == "datapack list available": return "Available: [file/bcce-it09-live-recipes]"
                if cmd == "datapack list enabled": return "Enabled: [vanilla]"
                if cmd == "reload": return "Reloading!"
                if cmd.startswith("recipe give"):
                    exists = (Path(tmp) / "bcce-it16-world/datapacks/bcce-it09-live-recipes"
                              "/data/bcce_ci/recipes/reload_probe.json").exists()
                    return "Gave 1 recipe to Dev_Test" if exists else "Unknown recipe"
                if cmd == "list": return "There are 0 of a max of 2 players online:"
                raise AssertionError(cmd)
            with mock.patch.object(probe, "rcon_command", side_effect=fake_rcon):
                with self.assertRaisesRegex(RuntimeError, "disconnected"):
                    probe.verify_live_recipe_reload("1.20.1-forge", Path(tmp), 25589, "secret", "Dev_Test", [])

    def test_charged_item_stack_fixtures_keep_enchantments_custom_data_and_damage(self):
        old = probe.charged_stack_snbt("1.20.1-forge")
        modern = probe.charged_stack_snbt("1.21.1-neoforge")
        self.assertIn("Count:1b", old)
        self.assertIn("BCCEProbeCharge:8765432L", old)
        self.assertIn("Enchantments:", old)
        self.assertIn("minecraft:enchantments", modern)
        self.assertIn("minecraft:custom_data", modern)
        self.assertIn("minecraft:damage", modern)
        for value in (old, modern):
            self.assertIn("minecraft:sharpness", value)
            self.assertIn("117", value)
        with self.assertRaises(ValueError):
            probe.charged_stack_snbt("26.3-neoforge")

    def test_charged_cargo_is_written_to_real_world_chest_and_verified_after_restart(self):
        requests = []
        snapshot = ("The block at 10, 64, 2 has the following block data: "
                    "{id: 'minecraft:diamond_sword',Damage:117,"
                    "Enchantments:[{id:'minecraft:sharpness'}],BCCEProbeCharge:8765432L,"
                    "BCCEProbeNested:{owner:'test'}}")
        def rcon(_port, _password, command):
            requests.append(command)
            if command.startswith("setblock "): return "Changed the block at 10, 64, 2"
            if command.startswith("data merge block "): return "Modified block data of 10, 64, 2"
            if command.startswith("data get block "): return snapshot
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            before = probe.verify_persisted_charged_cargo(25589, "secret", "1.20.1-forge",
                                                         (0, 64, 0), expected=None)
            after = probe.verify_persisted_charged_cargo(25589, "secret", "1.20.1-forge",
                                                        (0, 64, 0), expected=before)
        self.assertEqual(before, after)
        self.assertEqual(sum(q.startswith("data merge") for q in requests), 1)
        self.assertEqual(sum(q.startswith("data get") for q in requests), 2)

    def test_cargo_change_across_real_world_restart_fails(self):
        with mock.patch.object(probe, "rcon_command", return_value=(
            "The block at 10,64,2 has the following block data: "
            "{id:minecraft:diamond_sword,Damage:117,sharpness:3,"
            "BCCEProbeCharge:8765432L,BCCEProbeNested:{}}")):
            with self.assertRaisesRegex(RuntimeError, "changed across the world restart"):
                probe.verify_persisted_charged_cargo(25589, "secret", "1.20.1-forge",
                                                    (0, 64, 0), expected="different")

    def test_missing_charged_component_is_never_silently_accepted(self):
        with mock.patch.object(probe, "rcon_command", return_value=(
            "The block at 10,64,2 has the following block data: "
            "{id:minecraft:diamond_sword,Damage:117,sharpness:3}")):
            with self.assertRaisesRegex(RuntimeError, "lost"):
                probe.verify_persisted_charged_cargo(25589, "secret", "1.20.1-forge",
                                                    (0, 64, 0), expected="saved")


class HeadlessWorldJoinDiagnosticsTest(unittest.TestCase):
    def test_real_clients_use_bounded_software_rendering_settings(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(probe, "ROOT", Path(tmp)):
            base, server, client = probe.create_run_directories(
                "1.21.X", "1.21.11-neoforge", {}, 25588, True,
                second_client=True)
            self.assertFalse((server / "options.txt").exists())
            for path in (client / "options.txt", base / "client-2/options.txt"):
                options = path.read_text(encoding="utf-8")
                self.assertIn("renderDistance:2", options)
                self.assertIn("skipMultiplayerWarning:true", options)
                self.assertIn("joinedFirstServer:true", options)
                self.assertIn("maxFps:10", options)
                self.assertIn("mipmapLevels:0", options)
                self.assertIn("narrator:0", options)
                self.assertTrue((path.parent / "mods").is_dir())

    def test_it16_26_3_has_explicit_safe_localhost_whitelist_policy(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(probe, "ROOT", Path(tmp)):
            _base, server, client = probe.create_run_directories(
                "26.X", "26.3-neoforge", {}, 25588, True)
            properties = (server / "server.properties").read_text()
            self.assertIn("server-ip=127.0.0.1", properties)
            self.assertIn("white-list=false", properties)
            self.assertIn("pause-when-empty-seconds=-1", properties)
            self.assertIn("simulationDistance:5", (client / "options.txt").read_text())

    def test_26_2_client_gets_real_compressed_nbt_saved_server(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(probe, "ROOT", Path(tmp)):
            _base, _server, client = probe.create_run_directories(
                "26.X", "26.2-neoforge", {}, 25588, True, second_client=True)
            for directory in (client, client.parent / "client-2"):
                payload = probe.gzip.decompress((directory / "servers.dat").read_bytes())
                self.assertTrue(payload.startswith(b"\x0a\x00\x00\x09\x00\x07servers\x0a\x00\x00\x00\x01"))
                self.assertIn(b"127.0.0.1:25588", payload)
                self.assertIn(b"BCCE IT16 localhost", payload)
                self.assertIn("simulationDistance:5", (directory / "options.txt").read_text())

    def test_26_2_live_ui_fallback_clicks_multiplayer_and_saved_server(self):
        commands = []
        def run(command, **_kwargs):
            commands.append(command)
            output = "WIDTH=854\nHEIGHT=480\n" if "getwindowgeometry" in command else ""
            return probe.subprocess.CompletedProcess(args=command, returncode=0, stdout=output, stderr="")
        with mock.patch.object(probe, "find_minecraft_window", return_value="2026"), \
             mock.patch.object(probe.subprocess, "run", side_effect=run), \
             mock.patch.object(probe.time, "sleep"):
            probe.open_26_2_saved_server_via_ui({"DISPLAY": ":402"})
        self.assertIn(["xdotool", "mousemove", "--window", "2026", "427", "264"], commands)
        self.assertIn(["xdotool", "mousemove", "--window", "2026", "427", "106"], commands)
        self.assertIn(["xdotool", "click", "--repeat", "2", "--delay", "150", "1"], commands)
        self.assertIn(["xdotool", "key", "--clearmodifiers", "Return"], commands)

    def test_26_2_no_window_is_not_a_fake_world_join(self):
        with mock.patch.object(probe, "find_minecraft_window", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "cannot locate"):
                probe.open_26_2_saved_server_via_ui({"DISPLAY": ":402"})

    def test_failed_block_identity_probe_reports_live_world_state(self):
        commands=[]
        def fake_rcon(_port, _password, command):
            commands.append(command)
            if command == "list":
                return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("setblock "):
                return "Changed the block"
            if command.startswith("execute if block "):
                return ""
            if command.startswith("data get block "):
                return "Target block is not a block entity"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=fake_rcon):
            with self.assertRaisesRegex(RuntimeError, "diagnostics=.*block_entity"):
                probe.verify_actual_modded_world(25589, "secret", "Dev_Test",
                                                 origin=(0,64,0), include_ae2=False,
                                                 include_create=True)
        self.assertTrue(any("minecraft:water" in cmd for cmd in commands))
        self.assertTrue(any("data get block" in cmd for cmd in commands))

    def test_timeout_thread_dump_skips_windows_and_absent_launcher(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "diagnostics.log"
            probe.dump_client_threads(None, destination)
            self.assertFalse(destination.exists())

    def test_thread_dump_only_attaches_to_client_process_descendants(self):
        class ClientLauncher:
            pid = 102
        commands = []
        def run(args, **kwargs):
            commands.append(args)
            if args[0] == "ps":
                return probe.subprocess.CompletedProcess(args, 0, stdout=(
                    "102 1 /usr/bin/env bash gradlew\n"
                    "104 102 java org.gradle.wrapper.GradleWrapperMain\n"
                    "105 104 java net.neoforged.devlaunch.Main\n"
                    "999 1 java net.neoforged.devlaunch.Main SERVER\n"))
            if args[0] == "jcmd":
                return probe.subprocess.CompletedProcess(args, 0, stdout="Threads: Render thread WAITING")
            raise AssertionError(args)
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "client-thread-dump.log"
            with mock.patch.object(probe.shutil, "which", return_value="/usr/bin/jcmd"), \
                    mock.patch.object(probe.subprocess, "run", side_effect=run):
                probe.dump_client_threads(ClientLauncher(), destination)
            self.assertIn("Render thread WAITING", destination.read_text())
            self.assertTrue(any(cmd[:2] == ["jcmd", "105"] for cmd in commands))
            self.assertFalse(any(cmd[:2] == ["jcmd", "999"] for cmd in commands))


class ModpackCoverageContractTest(unittest.TestCase):
    """The lock, Gradle profiles, real join workflow and declared coverage must agree."""

    def test_all_seven_targets_have_strict_modpack_lock_and_required_jei_jade(self):
        config = json.loads(probe.LOCK.read_text(encoding="utf-8"))
        self.assertEqual(config["schema"], 2)
        self.assertEqual(set(config["targets"]), set(probe.TARGETS))
        for target in probe.TARGETS:
            with self.subTest(target=target):
                entry = probe.validate_modpack_lock(target)
                props = probe.target_properties(target)
                self.assertEqual(entry["generation"], props["build.generation"])
                self.assertEqual(tuple(entry["present"][x] for x in probe.OPTIONAL_NAMES),
                                 probe.EXPECTED_PRESENCE[target])
                for mod in ("jei", "jade"):
                    self.assertTrue(probe.native_mod_present(props, mod))

    def test_ic2_requires_top_and_curios_forestry_requires_a_real_runtime(self):
        lock = json.loads(probe.LOCK.read_text(encoding="utf-8"))
        self.assertTrue(lock["targets"]["1.19.2-forge"]["present"]["ic2"])
        self.assertEqual(lock["targets"]["1.19.2-forge"]["mods"]["theoneprobe"], "KCnKC3d7")
        self.assertEqual(lock["targets"]["1.19.2-forge"]["mods"]["curios"], "uUAY30IE")
        for target in ("1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge"):
            with self.subTest(target=target):
                self.assertTrue(probe.validate_modpack_lock(target)["present"]["forestry"])

    def test_ae2_requires_guideme_beyond_1192_and_negative_profile_removes_both(self):
        for target, expected in probe.EXPECTED_PRESENCE.items():
            if not expected[0]:
                continue
            with self.subTest(target=target):
                _, full = probe.select_locked_mods(target, without_ae2=False)
                _, trimmed = probe.select_locked_mods(target, without_ae2=True)
                self.assertIn("ae2", full)
                self.assertNotIn("ae2", trimmed)
                self.assertNotIn("guideme", trimmed)
                if target != "1.19.2-forge":
                    self.assertIn("guideme", full)

    def test_missing_required_loader_pins_or_extra_mods_fail_closed(self):
        lock = json.loads(probe.LOCK.read_text(encoding="utf-8"))
        for target, name in (("26.2-neoforge", "guideme"),
                             ("1.19.2-forge", "theoneprobe"),
                             ("1.20.1-forge", "ae2")):
            faulty = json.loads(json.dumps(lock))
            del faulty["targets"][target]["mods"][name]
            with tempfile.TemporaryDirectory() as directory:
                lock_file = Path(directory) / "bad-lock.json"
                lock_file.write_text(json.dumps(faulty), encoding="utf-8")
                with self.subTest(target=target, missing=name), mock.patch.object(probe, "LOCK", lock_file):
                    with self.assertRaises(ValueError):
                        probe.validate_modpack_lock(target)
        faulty = json.loads(json.dumps(lock))
        faulty["targets"]["26.3-neoforge"]["mods"]["surprise_mod"]="ABCD1234"
        with tempfile.TemporaryDirectory() as directory:
            lock_file = Path(directory) / "bad-lock.json"
            lock_file.write_text(json.dumps(faulty), encoding="utf-8")
            with mock.patch.object(probe, "LOCK", lock_file):
                with self.assertRaisesRegex(ValueError, "unreviewed"):
                    probe.validate_modpack_lock("26.3-neoforge")

    def test_jei_jade_cannot_be_dropped_from_compatible_gradle_profile(self):
        props = probe.target_properties("26.3-neoforge")
        with mock.patch.object(probe, "target_properties", return_value={k:v for k,v in props.items() if k != "deps.jade"}):
            with self.assertRaisesRegex(ValueError, "JADE"):
                probe.validate_modpack_lock("26.3-neoforge")
        with mock.patch.object(probe, "target_properties", return_value={**props, "compat.jei.enabled": "false"}):
            with self.assertRaisesRegex(ValueError, "JEI"):
                probe.validate_modpack_lock("26.3-neoforge")

    def test_runtime_client_log_must_contain_both_mandatory_mods(self):
        with tempfile.TemporaryDirectory() as folder:
            game_log, launcher = Path(folder)/"latest.log", Path(folder)/"gradle.log"
            launcher.write_text("Gradle dependencies: jei jade",encoding="utf-8")
            for snippet in ("", "Loaded Just Enough Items", "Loaded Jade", "Jade not loaded; JEI installed"):
                game_log.write_text(snippet, encoding="utf-8")
                with self.subTest(snippet=snippet), self.assertRaises(RuntimeError):
                    probe.verify_mandatory_client_mods(game_log, launcher)
            game_log.write_text("[mod loading] Just Enough Items loaded\n[mod loading] Jade loaded\n", encoding="utf-8")
            probe.verify_mandatory_client_mods(game_log, launcher)

    def test_real_world_probe_checks_forestry_and_ic2_block_ids(self):
        commands=[]
        def rcon(port, password, command):
            commands.append(command)
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command == "data get entity Dev_Test Pos": return "Dev_Test has the following entity data: [0d, 64d, 0d]"
            if command.startswith("setblock "): return "Changed the block"
            if command.startswith("execute if block "): return "Test passed, count: 1"
            if command.startswith("data get block "): return "The block has the following block data"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            probe.verify_actual_modded_world(25589, "secret", "Dev_Test", origin=None,
                include_forestry=True, include_ic2=True)
        self.assertTrue(any(cmd.endswith("forestry:apiary") for cmd in commands))
        self.assertTrue(any(cmd.endswith("ic2:generator") for cmd in commands))
        self.assertTrue(any("forestry:apiary" in cmd for cmd in commands if cmd.startswith("execute if block ")))
        self.assertTrue(any("ic2:generator" in cmd for cmd in commands if cmd.startswith("execute if block ")))

    def test_standard_ci_runs_real_world_join_for_all_targets_without_separate_workflow(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        for target in probe.TARGETS:
            self.assertIn("- target: " + target, workflow)
        self.assertIn('Start dedicated server, join world with client and rejoin (IT16)', workflow)
        self.assertIn('python3 scripts/ci-modpack-world-join.py', workflow)
        self.assertIn('flags+=(--chunk-cycle)', workflow)
        self.assertIn('flags+=(--smoke-only)', workflow)
        self.assertIn('run-modpack-join/${{ matrix.generation }}/${{ matrix.target }}/result.json', workflow)
        self.assertNotIn('Smoke-test production jar', workflow)
        self.assertNotIn('Client smoke-test production target', workflow)
        self.assertIn('SERVER_RUNTIME_PROFILE=base bash scripts/ci-server-smoke.sh', workflow)
        # The real client/server join belongs to GitHub Actions only.
        # Local CI deliberately remains validators + builds + GameTests.
        runner = (ROOT / "scripts/run-ci-local.py").read_text()
        self.assertNotIn('Join world with client and rejoin [{target}]', runner)
        self.assertNotIn('scripts/ci-modpack-world-join.py', runner)
        self.assertIn('GameTests -> artifacts', runner)
        powershell_runner = (ROOT / "run-ci-local.ps1").read_text()
        self.assertIn('Client and dedicated-server smoke tests are GitHub CI-only.', powershell_runner)
        self.assertFalse((ROOT / ".github/workflows/modpack-world-join.yml").exists())

    def test_required_optional_mod_dependencies_are_pinned(self):
        lock = json.loads((ROOT / "build-config/modpack-world-join-lock.json").read_text())
        self.assertEqual(lock["targets"]["1.21.1-neoforge"]["mods"]["patchouli"], "h6hKI2ob")
        self.assertEqual(lock["targets"]["26.1.2-neoforge"]["mods"]["guideme"], "Vvte0XiA")
        properties = (ROOT / "build-config/targets.properties").read_text()
        self.assertIn('target.26.2-neoforge.deps.jei=maven.modrinth:u6dRKJwZ:2Uf4rjR5', properties)
        self.assertTrue(probe.validate_modpack_lock("1.21.1-neoforge"))

    def test_forge_userdev_external_mods_are_remapped_and_not_copied_raw(self):
        deps = {"ae2": "U8JDlAX0", "modernfix": "dFQ9fgvb"}
        flag = probe.forge_remap_gradle_argument("1.20.1-forge", deps)
        self.assertEqual(flag, '-Pci_world_join_external_mods=ae2:U8JDlAX0,modernfix:dFQ9fgvb')
        self.assertIsNone(probe.forge_remap_gradle_argument("1.21.1-neoforge", deps))
        with self.assertRaisesRegex(ValueError, 'unconfigured external'):
            probe.forge_remap_gradle_argument("1.20.1-forge", {"untrusted": "ABC12345"})
        with self.assertRaisesRegex(ValueError, 'immutable'):
            probe.forge_remap_gradle_argument("1.20.1-forge", {"ae2": "ae2@not-immutable"})
        gradle = (ROOT / "build-logic/loaders/forge-target.gradle").read_text()
        self.assertIn('ci_world_join_external_mods', gradle)
        self.assertIn('runtimeOnly fg.deobf("maven.modrinth:', gradle)

    def test_headless_world_join_uses_null_openal_driver(self):
        env = {}
        probe.prepare_headless_client_environment("1.21.11-neoforge", env)
        self.assertEqual(env["ALSOFT_DRIVERS"], "null")
        self.assertEqual(env["LIBGL_ALWAYS_SOFTWARE"], "1")
        self.assertNotIn("VK_DRIVER_FILES", env)
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "client.log"
            log.write_text("Failed to create backend OpenGL\nFailed to create backend Vulkan\n")
            with self.assertRaisesRegex(RuntimeError, "both OpenGL and Vulkan"):
                probe.fail_fast_on_client_runtime_errors(log)
            log.write_text("Failed to open OpenAL device\n")
            with self.assertRaisesRegex(RuntimeError, "sound initialization"):
                probe.fail_fast_on_client_runtime_errors(log)

    def test_modrinth_jar_integrity_and_target_identity_fail_closed(self):
        class FakeResponse:
            def __init__(self, payload): self.payload=payload
            def __enter__(self): return self
            def __exit__(self,*args): return False
            def read(self): return self.payload
        jar=b"mock jar content for pinned mod"
        digest=hashlib.sha512(jar).hexdigest()
        meta=dict(id="ABC12345",project_id="XxWD5pD3",game_versions=["1.20.1"],
                  loaders=["forge"],files=[dict(filename="ae2.jar",primary=True,
                  hashes=dict(sha512=digest),url="https://example.org/ae2.jar")])
        def fake_urlopen(req,timeout=None):
            url=req.full_url
            return FakeResponse(jar if url.endswith(".jar") else json.dumps(meta).encode("utf-8"))
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(probe.urllib.request,"urlopen",side_effect=fake_urlopen):
            path=probe.download_locked_mod("ABC12345","1.20.1-forge",Path(folder),mod_name="ae2")
            self.assertEqual(path.read_bytes(),jar)
            meta["project_id"]="wrong"
            with self.assertRaisesRegex(RuntimeError,"Expected Modrinth project"):
                probe.download_locked_mod("ABC12345","1.20.1-forge",Path(folder),mod_name="ae2")
            meta["project_id"]="XxWD5pD3"
            meta["game_versions"]=["26.3"]
            with self.assertRaisesRegex(RuntimeError,"does not match"):
                probe.download_locked_mod("ABC12345","1.20.1-forge",Path(folder),mod_name="ae2")

    def test_exact_version_pin_disallows_wrong_game_or_loader(self):
        data=[dict(id="QWERTY12",version_number="21.1.19",project_id="Ck4E7v7R",
                   game_versions=["1.21.1"],loaders=["neoforge"],
                   files=[dict(filename="guideme.jar",primary=True,
                               hashes={"sha512":hashlib.sha512(b"guideme").hexdigest()},
                               url="https://example.org/guideme.jar")])]
        class FakeResponse:
            def __init__(self,b): self.b=b
            def __enter__(self):return self
            def __exit__(self,*a):return False
            def read(self):return self.b
        def urlopen(req,timeout=None):
            return FakeResponse(b"guideme" if req.full_url.endswith(".jar") else json.dumps(data).encode())
        with tempfile.TemporaryDirectory() as folder, mock.patch.object(probe.urllib.request,"urlopen",side_effect=urlopen):
            path=probe.download_locked_mod("guideme@21.1.19","1.21.1-neoforge",Path(folder),mod_name="guideme")
            self.assertEqual(path.read_bytes(),b"guideme")
            with self.assertRaisesRegex(RuntimeError,"No unique"):
                probe.download_locked_mod("guideme@21.1.19","26.3-neoforge",Path(folder),mod_name="guideme")


class OptionalCreateRealWorldTest(unittest.TestCase):
    def test_profile_matches_both_gradle_loader_registries(self):
        self.assertEqual(probe.modpack_runtime_profile(without_create=False), "modpack")
        self.assertEqual(probe.modpack_runtime_profile(without_create=True), "modpack-no-create")
        for loader in ("forge", "neoforge"):
            path = probe.ROOT / "build-logic/loaders" / (loader + "-target.gradle")
            source = path.read_text(encoding="utf-8")
            self.assertIn("'modpack-no-create'", source)
            self.assertIn("ciRuntimeProfile == 'modpack-no-create' && ['jei', 'jade', 'forestry', 'ic2'].contains(profile)", source)
            self.assertIn("ciRuntimeProfile == 'modpack' && ['jei', 'jade', 'create', 'forestry', 'ic2'].contains(profile)", source)

    def test_runtime_absence_requires_specific_unknown_block_error(self):
        requests = []
        def rcon(_port, _password, command):
            requests.append(command)
            return "Unknown block type 'create:shaft'"
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            probe.verify_create_absent(25589, "secret", "12 64 2")
        self.assertEqual(requests, ["setblock 12 64 2 create:shaft"])

    def test_runtime_absence_rejects_present_or_nonresponsive_registry(self):
        for reply in ("Changed the block at 12, 64, 2", "", "Unknown command", "Could not set block"):
            with self.subTest(reply=reply), mock.patch.object(probe, "rcon_command", return_value=reply):
                with self.assertRaisesRegex(RuntimeError, "Create is present"):
                    probe.verify_create_absent(25589, "secret", "12 64 2")

    def test_world_probe_without_create_checks_real_bcce_and_ae2_blocks(self):
        commands = []
        def rcon(_port, _password, command):
            commands.append(command)
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("setblock "):
                if command.endswith("create:shaft"):
                    return "Unknown block type 'create:shaft'"
                return "Changed the block at 0, 64, 0"
            if command.startswith("execute if block "):
                return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            self.assertEqual(probe.verify_actual_modded_world(
                25589, "secret", "Dev_Test", origin=(0, 64, 0), include_create=False), (0, 64, 0))
        self.assertEqual(sum(cmd.endswith("create:shaft") for cmd in commands), 1)
        self.assertTrue(any("ae2:controller" in cmd for cmd in commands))
        self.assertTrue(any("buildcraftfactory:autoworkbench_item" in cmd for cmd in commands))

    def test_optional_ae2_removal_uses_real_mod_lists_for_every_target(self):
        for target in ("1.20.1-forge", "1.21.1-neoforge"):
            with self.subTest(target=target):
                generation, full = probe.select_locked_mods(target, without_ae2=False)
                selected_generation, trimmed = probe.select_locked_mods(target, without_ae2=True)
                self.assertEqual(generation, selected_generation)
                self.assertIn("ae2", full)
                self.assertNotIn("ae2", trimmed)
                self.assertEqual(trimmed, {k: v for k, v in full.items() if k not in ("ae2", "guideme")})
                self.assertIn("modernfix", trimmed)

    def test_no_ae2_client_and_server_directories_cannot_contain_ae2_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            cache = base / "ci-modpack-cache"
            cache.mkdir()
            for name in ("ae2", "modernfix"):
                (cache / (name + "-probe.jar")).write_bytes(b"test-only file")
            with mock.patch.object(probe, "ROOT", base), mock.patch.object(probe, "LOCK", probe.LOCK):
                _, selected = probe.select_locked_mods("1.20.1-forge", without_ae2=True)
                # Make exactly the selected mod version available to offline mode.
                (cache / (selected["modernfix"] + "-modernfix.jar")).write_bytes(b"modernfix probe")
                home, server, client = probe.create_run_directories(
                    "old", "1.20.1-forge", selected, 25588, True, second_client=True)
            for path in (server, client, home / "client-2"):
                files = [p.name for p in (path / "mods").iterdir()]
                self.assertEqual(files, [])  # ForgeGradle remaps the version-locked dependency.
            flag = probe.forge_remap_gradle_argument("1.20.1-forge", selected)
            self.assertIn("modernfix:" + selected["modernfix"], flag)
            self.assertNotIn("ae2:", flag)

    def test_it14_removes_exact_ae2_jar_from_every_stopped_game_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            for dirname in ("server", "client", "client-2"):
                mod_dir = base / dirname / "mods"
                mod_dir.mkdir(parents=True)
                (mod_dir / "ae2id-applied-energistics.jar").write_bytes(b"locked ae2")
                (mod_dir / "modernfixid-modernfix.jar").write_bytes(b"other mod")
            probe.remove_pinned_ae2_after_shutdown(base, "ae2id", two_players=True)
            for dirname in ("server", "client", "client-2"):
                mods = [path.name for path in (base / dirname / "mods").iterdir()]
                self.assertEqual(mods, ["modernfixid-modernfix.jar"])

    def test_it14_never_partially_deletes_when_one_ae2_copy_is_missing(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            for dirname in ("server", "client"):
                mod_dir = base / dirname / "mods"
                mod_dir.mkdir(parents=True)
                (mod_dir / "ae2id-applied-energistics.jar").write_bytes(b"locked ae2")
            (base / "client" / "mods" / "ae2id-applied-energistics.jar").unlink()
            with self.assertRaisesRegex(RuntimeError, "expected exactly one locked AE2"):
                probe.remove_pinned_ae2_after_shutdown(base, "ae2id", two_players=False)
            self.assertTrue((base / "server/mods/ae2id-applied-energistics.jar").exists())

    def test_it14_ae2_absent_after_restart_probe_does_not_accept_stale_registry(self):
        def rcon(_port, _password, command):
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("execute if block "): return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            if command.startswith("setblock "): return "Changed the block at 0, 64, 0"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, "AE2 is present"):
                probe.verify_actual_modded_world(
                    25589, "secret", "Dev_Test", origin=(0, 64, 0), include_ae2=False)

    def test_runtime_ae2_absence_requires_unknown_registry_id(self):
        with mock.patch.object(probe, "rcon_command", return_value="Unknown block type 'ae2:controller'") as rcon:
            probe.verify_ae2_absent(25589, "secret", "14 64 2")
        rcon.assert_called_once_with(25589, "secret", "setblock 14 64 2 ae2:controller")
        for reply in ("Changed the block", "", "Unknown command", "Block is not in a loaded chunk"):
            with self.subTest(reply=reply), mock.patch.object(probe, "rcon_command", return_value=reply):
                with self.assertRaisesRegex(RuntimeError, "AE2 is present"):
                    probe.verify_ae2_absent(25589, "secret", "14 64 2")

    def test_world_without_ae2_has_create_but_no_ae2_registry(self):
        commands = []
        def rcon(_port, _password, command):
            commands.append(command)
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("setblock "):
                if command.endswith("ae2:controller"):
                    return "Unknown block type 'ae2:controller'"
                return "Changed the block at 0, 64, 0"
            if command.startswith("execute if block "): return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            self.assertEqual(probe.verify_actual_modded_world(
                25589, "secret", "Dev_Test", origin=(0, 64, 0), include_ae2=False), (0, 64, 0))
        self.assertEqual(sum(cmd.endswith("ae2:controller") for cmd in commands), 1)
        self.assertTrue(any("create:shaft" in cmd for cmd in commands))

    def test_world_without_ae2_rejects_ae2_present(self):
        def rcon(_port, _password, command):
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("setblock "): return "Changed the block at 0, 64, 0"
            if command.startswith("execute if block "): return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, "AE2 is present"):
                probe.verify_actual_modded_world(
                    25589, "secret", "Dev_Test", origin=(0, 64, 0), include_ae2=False)

    def test_world_probe_without_create_does_not_pass_if_create_loaded(self):
        def rcon(_port, _password, command):
            if command == "list": return "There are 1 of a max of 2 players online: Dev_Test"
            if command.startswith("setblock "): return "Changed the block at 0, 64, 0"
            if command.startswith("execute if block "): return "Test passed, count: 1"
            if command.startswith("data get block "):
                return "The block at 0, 64, 0 has the following block data: {id:'buildcraftfactory:tank'}"
            raise AssertionError(command)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, "Create is present"):
                probe.verify_actual_modded_world(
                    25589, "secret", "Dev_Test", origin=(0, 64, 0), include_create=False)


# These are pure protocol checks. Actual world/rejoin execution is performed by
# the separate weekly GitHub Actions integration workflow.


class TwoPlayerFactoryProbeTest(unittest.TestCase):
    def test_rcon_list_parser_accepts_only_exact_distinct_players(self):
        self.assertEqual(probe.online_players(
            "There are 2 of a max of 2 players online: Dev, BCCE_Tester2"),
            {"Dev", "BCCE_Tester2"})
        self.assertEqual(probe.online_players(
            "There are 0 of a max of 2 players online:"), set())
        with self.assertRaises(RuntimeError):
            probe.online_players("Loader successfully initialized BCCE_Tester2")
        with self.assertRaises(RuntimeError):
            probe.online_players("There are 2 of a max of 2 players online: Dev, Dev")

    def test_two_player_factory_probe_places_machine_only_on_first_join(self):
        actions = []
        def command(_port, _password, request):
            actions.append(request)
            if request == "list":
                return "There are 2 of a max of 2 players online: Dev, BCCE_Tester2"
            if request.startswith("setblock"):
                return "Changed the block at 12, 64, 2"
            if request.startswith("execute if block"):
                return "Test passed, count: 1"
            if request.startswith("data get block"):
                return "The block at 12, 64, 2 has the following block data: {id: 'buildcraftfactory:entity_autobench'}"
            raise AssertionError(request)
        with mock.patch.object(probe, "rcon_command", side_effect=command):
            probe.verify_second_client_and_factory(25589, "pw", "Dev", (0, 64, 0), first_join=True)
            self.assertEqual(sum(x.startswith("setblock ") for x in actions), 2)
            actions.clear()
            probe.verify_second_client_and_factory(25589, "pw", "Dev", (0, 64, 0), first_join=False)
            self.assertFalse(any(x.startswith("setblock ") for x in actions))

    def test_two_player_probe_rejects_missing_second_client(self):
        with mock.patch.object(probe, "rcon_command", return_value=
                "There are 1 of a max of 2 players online: Dev"):
            with self.assertRaisesRegex(RuntimeError, "Expected online"):
                probe.verify_second_client_and_factory(25589, "pw", "Dev", (0, 64, 0), first_join=False)

    def test_two_player_probe_fails_if_factory_does_not_survive_restart(self):
        def command(_port, _password, request):
            if request == "list":
                return "There are 2 of a max of 2 players online: Dev, BCCE_Tester2"
            if request.startswith("execute if block"):
                return "Test failed"  # conditional detected the missing factory
            raise AssertionError(request)
        with mock.patch.object(probe, "rcon_command", side_effect=command):
            with self.assertRaisesRegex(RuntimeError, "factory changed"):
                probe.verify_second_client_and_factory(25589, "pw", "Dev", (0, 64, 0), first_join=False)

    def test_secondary_client_isolation_copies_mods_to_all_three_directories(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "ci-modpack-cache").mkdir()
            for ident in ("modA", "modB"):
                (base / "ci-modpack-cache" / (ident + "-probe.jar")).write_bytes(b"dummy jar data")
            with mock.patch.object(probe, "ROOT", base):
                home, server, client = probe.create_run_directories(
                    "1.21.X", "1.21.1-neoforge", {"one": "modA", "two": "modB"},
                    25588, True, second_client=True)
            for path in (server, client, home / "client-2"):
                self.assertTrue((path / "mods" / "modA-probe.jar").is_file())
                self.assertTrue((path / "mods" / "modB-probe.jar").is_file())
            self.assertTrue((home / "client-2" / "logs").is_dir())

    def test_forge_world_join_leaves_raw_mod_dirs_empty_for_fg_deobf(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "ci-modpack-cache").mkdir()
            (base / "ci-modpack-cache" / "U8JDlAX0-ae2.jar").write_bytes(b"verified fixture")
            with mock.patch.object(probe, "ROOT", base):
                home, server, client = probe.create_run_directories(
                    "old", "1.19.2-forge", {"ae2": "U8JDlAX0"},
                    25588, True, second_client=True)
            for run_dir in (server, client, home / "client-2"):
                self.assertEqual(list((run_dir / "mods").glob("*.jar")), [])

    def test_gradle_client_slot_isolation_is_configured_for_both_loaders(self):
        for loader in ("forge", "neoforge"):
            with self.subTest(loader=loader):
                build = (ROOT / "build-logic" / "loaders" / f"{loader}-target.gradle").read_text()
                self.assertIn("ci_world_join_client_slot", build)
                self.assertIn("ci_world_join_username", build)
                self.assertIn("'client-2'", build)
                self.assertIn("'--username'", build)


class RemoteChunkLifecycleTest(unittest.TestCase):
    """Fast RCON contract tests; real chunk unloading runs in modpack CI."""

    class DummyProcess:
        def poll(self):
            return None

    def fake_server(self, origin=(0, 64, 0), *, refuse_unload=False, bad_machine=False,
                    change_after_unload=False):
        chest, machine, near, far = probe.chunk_fixture_positions(origin)
        state = {"pos": origin, "loaded": False, "machine": False,
                 "stack": None, "commands": [], "phase": 0}
        stack = ("The block at 257, 219, 257 has the following block data: "
                 "{id:'minecraft:diamond_sword',Damage:117,"
                 "Enchantments:[{id:'minecraft:sharpness'}],"
                 "BCCEProbeCharge:8765432L,BCCEProbeNested:{owner:'test'}}")

        def rcon(_port, _password, command):
            state["commands"].append(command)
            if command.startswith("gamemode creative"):
                return "Set Dev_Test's game mode to Creative Mode"
            if command.startswith("tp "):
                xyz = tuple(map(int, command.split()[-3:]))
                state["pos"] = xyz
                if xyz == near:
                    state["loaded"] = True
                elif xyz == far:
                    state["loaded"] = refuse_unload
                    state["phase"] += 1
                else:
                    state["loaded"] = False
                return f"Teleported Dev_Test to {xyz}"
            if command.startswith("data get entity "):
                x, y, z = state["pos"]
                return f"Dev_Test has the following entity data: [{x}.0d, {y}.0d, {z}.0d]"
            if command == f"setblock {chest} minecraft:chest":
                if not state["loaded"]: return "Target position is not loaded"
                return "Changed the block"
            if command == f"setblock {machine} buildcraftfactory:autoworkbench_item":
                state["machine"] = True
                return "Changed the block"
            if command.startswith(f"data merge block {chest} "):
                state["stack"] = stack
                return "Modified block data"
            if command == f"data get block {chest} Items[0]":
                if not state["loaded"]: return "Target position is not loaded"
                return (state["stack"] + " BUT_CORRUPTED" if change_after_unload and state["phase"]
                        else state["stack"])
            if command == f"data get block {chest}":
                return "The block has the following block data: {Items:[]}" if state["loaded"] else "Target position is not loaded"
            if command == f"data get block {machine}":
                if bad_machine: return "Target is not a block entity"
                return ("The block has the following block data: {id:'buildcraftfactory:autoworkbench_item'}"
                        if state["loaded"] and state["machine"] else "Target position is not loaded")
            if command == "list":
                return "There are 1 of a max of 2 players online: Dev_Test"
            raise AssertionError(f"Unexpected RCON command: {command}")

        return rcon, state

    def test_remote_fixture_is_outside_spawn_radius_even_for_negative_spawn(self):
        chest, machine, near, far = probe.chunk_fixture_positions((-129, 70, -225))
        self.assertEqual(near, (127, 220, 31))
        self.assertEqual(far, (895, 220, 799))
        self.assertEqual(chest, "128 219 32")
        self.assertEqual(machine, "130 219 32")
        self.assertGreater(abs(far[0] - near[0]), 600)

    def test_chunk_unload_parser_rejects_ambiguous_errors(self):
        self.assertFalse(probe.chunk_loaded_reply("The target position is not loaded"))
        self.assertTrue(probe.chunk_loaded_reply("The block has the following block data: {}"))
        for bad in ("No block entity at position", "Unknown command", "Error parsing block position"):
            with self.subTest(reply=bad), self.assertRaises(RuntimeError):
                probe.chunk_loaded_reply(bad)

    def test_real_remote_chunk_unloads_and_restores_machine_and_charged_item(self):
        rcon, state = self.fake_server()
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            expected = probe.verify_chunk_unload_and_machine_persistence(
                25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                [self.DummyProcess()], expected=None)
            self.assertEqual(state["pos"], (0, 64, 0))
            self.assertEqual(state["phase"], 1)
            self.assertTrue(state["machine"])
            # After a real dedicated server restart there is no player at the
            # remote site. It can only be loaded again by an actual teleport.
            state["loaded"] = False
            actual = probe.verify_chunk_unload_and_machine_persistence(
                25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                [self.DummyProcess()], expected=expected)
        self.assertEqual(actual, expected)
        self.assertEqual(state["pos"], (0, 64, 0))
        self.assertEqual(sum(c.startswith("data merge block") for c in state["commands"]), 1)
        self.assertEqual(sum(c.startswith("setblock") for c in state["commands"]), 2)
        self.assertIn("8765432", actual)

    def test_chunk_never_unloaded_is_not_false_success(self):
        rcon, state = self.fake_server(refuse_unload=True)
        def immediate_timeout(label, predicate, _procs, _timeout):
            if not predicate():
                raise TimeoutError(label)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon), \
             mock.patch.object(probe, "wait_until", side_effect=immediate_timeout):
            with self.assertRaisesRegex(TimeoutError, "remote chunk genuinely unloaded"):
                probe.verify_chunk_unload_and_machine_persistence(
                    25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                    [self.DummyProcess()], expected=None)

    def test_missing_real_machine_block_entity_is_failure(self):
        rcon, _ = self.fake_server(bad_machine=True)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, "Cannot determine chunk load state"):
                probe.verify_chunk_unload_and_machine_persistence(
                    25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                    [self.DummyProcess()], expected=None)

    def test_charge_or_components_change_after_unload_is_failure(self):
        rcon, _ = self.fake_server(change_after_unload=True)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, "changed after the actual chunk unload"):
                probe.verify_chunk_unload_and_machine_persistence(
                    25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                    [self.DummyProcess()], expected=None)

    def test_restart_with_orphaned_chunk_ticket_cannot_pass(self):
        rcon, state = self.fake_server()
        _, _, near, _ = probe.chunk_fixture_positions((0, 64, 0))
        state.update(loaded=True, machine=True, stack=(
            "minecraft:diamond_sword sharpness 117 8765432 BCCEProbeNested"))
        def immediate_timeout(label, predicate, _procs, _timeout):
            if not predicate(): raise TimeoutError(label)
        with mock.patch.object(probe, "rcon_command", side_effect=rcon), \
             mock.patch.object(probe, "wait_until", side_effect=immediate_timeout):
            with self.assertRaisesRegex(TimeoutError, "after server restart"):
                probe.verify_chunk_unload_and_machine_persistence(
                    25589, "secret", "1.20.1-forge", "Dev_Test", (0, 64, 0),
                    [self.DummyProcess()], expected=state["stack"])
        self.assertNotEqual(state["pos"], near)

    def test_teleport_cannot_be_acknowledged_by_unrelated_server_reply(self):
        with mock.patch.object(probe, "rcon_command", return_value="Unknown command"):
            with self.assertRaisesRegex(RuntimeError, "did not confirm teleport"):
                probe.teleport_and_confirm(25589, "secret", "Dev_Test", (256, 220, 256), [])


class ChunkCycleWorkflowTest(unittest.TestCase):
    def test_full_fixture_targets_run_chunk_cycle_in_standard_ci(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn('1.20.1-forge|1.21.1-neoforge)', workflow)
        self.assertIn('flags+=(--chunk-cycle)', workflow)
        self.assertIn('flags+=(--smoke-only)', workflow)
        self.assertFalse((ROOT / ".github/workflows/modpack-world-join.yml").exists())

class ClientResourceReloadTest(unittest.TestCase):
    def test_stale_startup_atlas_cannot_count_as_client_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("Reloading ResourceManager: startup\n"
                           "Created: minecraft:textures/atlas/blocks.png-atlas\n")
            initial_length = len(log.read_text())
            self.assertEqual(probe.client_reload_log_state(log, initial_length), (False, False))
            with log.open("a") as stream:
                stream.write("Reloading ResourceManager: vanilla, mod_resources\n")
            self.assertEqual(probe.client_reload_log_state(log, initial_length), (True, False))
            with log.open("a") as stream:
                stream.write("Created: minecraft:textures/atlas/blocks.png-atlas\n")
            self.assertEqual(probe.client_reload_log_state(log, initial_length), (True, True))

    def test_log_truncation_cannot_forge_resource_reload(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("startup" * 10)
            before = len(log.read_text())
            log.write_text("Reloading ResourceManager: suspicious\n")
            with self.assertRaisesRegex(RuntimeError, "truncated"):
                probe.client_reload_log_state(log, before)

    def test_only_one_visible_minecraft_window_may_receive_hotkey(self):
        def result(stdout, rc=0):
            return probe.subprocess.CompletedProcess(args=[], returncode=rc, stdout=stdout, stderr="")
        with mock.patch.object(probe.subprocess, "run", return_value=result("12345\n")):
            self.assertEqual(probe.find_minecraft_window({"DISPLAY": ":400"}), "12345")
        with mock.patch.object(probe.subprocess, "run", return_value=result("", 1)):
            self.assertIsNone(probe.find_minecraft_window({"DISPLAY": ":400"}))
        with mock.patch.object(probe.subprocess, "run", return_value=result("12345\n12346\n")):
            with self.assertRaisesRegex(RuntimeError, "Expected one visible"):
                probe.find_minecraft_window({"DISPLAY": ":400"})

    def test_actual_client_hotkey_requires_reload_and_atlas_and_online_player(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("startup ResourceManager and preexisting block atlas\n")
            commands = []
            def run(cmd, **_kwargs):
                commands.append(cmd)
                if "key" in cmd:
                    with log.open("a") as stream:
                        stream.write("Reloading ResourceManager: vanilla, mod_resources\n")
                        stream.write("Created: minecraft:textures/atlas/blocks.png-atlas\n")
                return probe.subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")
            def immediate_wait(label, predicate, _procs, _seconds):
                if not predicate():
                    raise TimeoutError(label)
            with mock.patch.object(probe, "find_minecraft_window", return_value="2026"), \
                 mock.patch.object(probe.subprocess, "run", side_effect=run), \
                 mock.patch.object(probe, "wait_until", side_effect=immediate_wait), \
                 mock.patch.object(probe, "require_online") as online:
                probe.verify_client_resource_reload(log, 25589, "secret", "Dev_Test", [], {"DISPLAY": ":400"})
                self.assertEqual([cmd[1] for cmd in commands], ["windowfocus", "key"])
                self.assertIn("F3+t", commands[-1])
                online.assert_called_once_with(25589, "secret", {"Dev_Test"})

    def test_client_hotkey_without_new_block_atlas_must_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("Created: minecraft:textures/atlas/blocks.png-atlas\n")
            def run(cmd, **_kwargs):
                if "key" in cmd:
                    with log.open("a") as stream:
                        stream.write("Reloading ResourceManager: vanilla, mod_resources\n")
                return probe.subprocess.CompletedProcess(args=cmd, returncode=0, stdout="", stderr="")
            def immediate_wait(label, predicate, _procs, _seconds):
                if not predicate():
                    raise TimeoutError(label)
            with mock.patch.object(probe, "find_minecraft_window", return_value="2026"), \
                 mock.patch.object(probe.subprocess, "run", side_effect=run), \
                 mock.patch.object(probe, "wait_until", side_effect=immediate_wait):
                with self.assertRaisesRegex(TimeoutError, "block atlas"):
                    probe.verify_client_resource_reload(log, 25589, "secret", "Dev_Test", [], {"DISPLAY": ":400"})

    def test_ui_mode_is_cli_only_and_incompatible_with_two_clients(self):
        self.assertFalse((ROOT / ".github/workflows/modpack-world-join.yml").exists())
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertNotIn('flags+=(--client-resource-reload)', workflow)
        with mock.patch.object(probe.sys, "argv", ["probe", "--target", "1.20.1-forge",
                                                       "--client-resource-reload", "--two-players"]):
            with self.assertRaises(SystemExit) as failure:
                probe.main()
            self.assertEqual(failure.exception.code, 2)

    def test_broken_bcce_model_after_f3t_is_a_hard_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "latest.log"
            log.write_text("Created: minecraft:textures/atlas/blocks.png-atlas\n")
            before = len(log.read_text())
            with log.open("a") as stream:
                stream.write("Reloading ResourceManager: vanilla, mod_resources\n")
                stream.write("Missing model for variant buildcrafttransport:item_pipe#normal\n")
                stream.write("Created: minecraft:textures/atlas/blocks.png-atlas\n")
            with self.assertRaisesRegex(RuntimeError, "BCCE model/texture"):
                probe.reject_client_resource_errors(log, before)
            probe.reject_client_resource_errors(log, len(log.read_text()))

class PersistedInFlightPipeProbeTest(unittest.TestCase):
    """IT01 infrastructure: do not accept a logged join as proof of restored pipe cargo."""

    def test_inflight_nbt_is_version_specific_and_preserves_every_component(self):
        for target in ("1.20.1-forge", "1.21.1-neoforge"):
            text = probe.pipe_travelling_item_snbt(target)
            self.assertIn('side:"WEST"', text)
            self.assertIn('tickFinished:1000', text)
            self.assertIn('toCenter:1b', text)
            self.assertIn('stack:{id:"minecraft:diamond_sword"', text)
            self.assertNotIn('Slot:0b', text)  # pipe stack, not chest slot encoding
            for key in probe.FLIGHT_STACK_KEYS:
                self.assertIn(key, text)
            if target.startswith('1.20'):
                self.assertIn('Count:1b', text)
                self.assertIn('tag:{Damage:117', text)
            else:
                self.assertIn('count:1', text)
                self.assertIn('"minecraft:custom_data"', text)
        with self.assertRaises(ValueError):
            probe.pipe_travelling_item_snbt("26.3-neoforge")

    def test_real_fixture_initializes_two_pipes_and_requires_live_item(self):
        commands = []
        def rcon(_port, _password, command):
            commands.append(command)
            if command.startswith('setblock '):
                return 'Changed the block at the test site'
            if command.startswith('data merge block '):
                return 'Modified block data of the test site'
            if command.startswith('data get block ') and 'pipe.flow.items[0]' in command:
                return ('Pipe data {stack:{id:"minecraft:diamond_sword",Damage:117,'
                        'enchantments:"sharpness",BCCEProbeCharge:8765432L,BCCEProbeNested:{owner:"test"},'
                        'custom_name:"IT16 charged sword",toCenter:1b,tickFinished:1000}')
            raise AssertionError(command)
        with mock.patch.object(probe, 'rcon_command', side_effect=rcon):
            probe.prepare_saved_inflight_pipe(25589, 'secret', '1.20.1-forge', (10, 67, -20))
        self.assertEqual(sum(cmd.startswith('setblock ') for cmd in commands), 4)
        self.assertEqual(sum(cmd.startswith('data merge block ') for cmd in commands), 2)
        self.assertIn('setblock 26 67 -18 buildcrafttransport:pipe_holder', commands)
        self.assertTrue(any('pipe.flow.items[0]' in cmd for cmd in commands))
        flow_command = next(cmd for cmd in commands if 'data merge block 26 67 -18' in cmd)
        self.assertIn('buildcrafttransport:cobblestone_item', flow_command)
        self.assertIn('BCCEProbeCharge:8765432L', flow_command)

    def test_staging_fails_when_real_pipe_does_not_accept_saved_nbt(self):
        def rcon(_port, _password, cmd):
            if cmd.startswith('setblock '): return 'Changed the block'
            if cmd.startswith('data merge block '): return 'No block entity found'
            raise AssertionError(cmd)
        with mock.patch.object(probe, 'rcon_command', side_effect=rcon):
            with self.assertRaisesRegex(RuntimeError, 'could not install'):
                probe.prepare_saved_inflight_pipe(25589, 'secret', '1.20.1-forge', (0, 64, 0))

    def test_staging_rejects_empty_or_unreadable_flow(self):
        for reply in ('Could not find element pipe.flow.items[0]', 'The following block data: []',
                      'Pipe data {stack:{id:"minecraft:diamond_sword"}}'):
            with self.subTest(reply=reply):
                with self.assertRaises(RuntimeError):
                    probe.verify_pipe_flow_snapshot(reply)

    def test_after_restart_requires_exact_item_empty_pipes_and_no_drops(self):
        commands = []
        charged = ('Slot:0b,Count:1b,minecraft:diamond_sword,Damage:117,sharpness,'
                   'BCCEProbeCharge:8765432L,BCCEProbeNested:{owner:"test"},'
                   'custom_name:"IT16 charged sword"')
        def rcon(_port, _password, cmd):
            commands.append(cmd)
            if cmd.startswith('execute if block '): return 'Test passed, count: 1'
            if cmd.endswith(' Items[0]'): return charged
            if cmd.endswith(' Items'): return '[{' + charged + '}]'
            if cmd.endswith(' pipe.flow.items'): return 'The following block data: []'
            if 'if entity @e[type=minecraft:item' in cmd: return 'Test failed'
            raise AssertionError(cmd)
        def immediately(label, predicate, processes, timeout):
            self.assertTrue(predicate())
            self.assertEqual(timeout, 180)
        with mock.patch.object(probe, 'rcon_command', side_effect=rcon), \
             mock.patch.object(probe, 'wait_until', side_effect=immediately):
            probe.verify_saved_inflight_pipe_delivered(25589, 'secret', (0, 64, 0), [])
        self.assertTrue(any(cmd.endswith('pipe.flow.items') for cmd in commands))
        self.assertTrue(any('if entity @e[type=minecraft:item' in cmd for cmd in commands))

    def test_duplicate_or_leftover_pipe_item_is_not_a_pass(self):
        charged = 'Slot:0b,Count:1b,minecraft:diamond_sword,117,sharpness,8765432,BCCEProbeNested,IT16 charged sword,owner'
        for fault in ('duplicate', 'in_pipe', 'drop'):
            def rcon(_port, _password, cmd):
                if cmd.startswith('execute if block '): return 'Test passed, count: 1'
                if cmd.endswith(' Items[0]'): return charged
                if cmd.endswith(' Items'):
                    return charged * (2 if fault == 'duplicate' else 1)
                if cmd.endswith(' pipe.flow.items'):
                    return charged if fault == 'in_pipe' else '[]'
                if 'if entity @e[type=minecraft:item' in cmd:
                    return 'Test passed, count: 1' if fault == 'drop' else 'Test failed'
                raise AssertionError(cmd)
            with self.subTest(fault=fault), \
                 mock.patch.object(probe, 'rcon_command', side_effect=rcon), \
                 mock.patch.object(probe, 'wait_until', side_effect=lambda _label, predicate, *_rest: self.assertTrue(predicate())):
                with self.assertRaises(RuntimeError):
                    probe.verify_saved_inflight_pipe_delivered(25589, 'secret', (0, 64, 0), [])

    def test_missing_pipe_after_restart_is_not_a_pass(self):
        with mock.patch.object(probe, 'rcon_command', return_value='Test failed'):
            with self.assertRaisesRegex(RuntimeError, 'pipeline block was missing'):
                probe.verify_saved_inflight_pipe_delivered(25589, 'secret', (0, 64, 0), [])
if __name__ == "__main__":
    unittest.main()
