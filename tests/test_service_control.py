import importlib.util
import io
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "service_control",
    Path(__file__).resolve().parents[1] / "pi/pilink-service-control.py",
)
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


def state(**values):
    return {
        "installed": True,
        "state": "inactive",
        "adapter": None,
        "enabled": False,
        "job": False,
        **values,
    }


class ServiceControlTest(unittest.TestCase):
    def test_failed_service_after_start_is_reported_as_failure(self):
        with patch("sys.argv", ["control", "apply", "--adapter", "hci1"]), patch.object(
            control.os, "geteuid", return_value=0
        ), patch.object(control, "mutation_lock"), patch.object(
            control, "control"
        ), patch.object(
            control, "status", return_value=state(state="failed")
        ), patch(
            "sys.stdout", new_callable=io.StringIO
        ) as output:
            self.assertEqual(control.main(), 1)
            self.assertEqual(json.loads(output.getvalue())["reason"], "start_failed")

    def test_empty_systemd_job_is_idle(self):
        with patch.object(
            control,
            "run",
            return_value="LoadState=loaded\nActiveState=inactive\nMainPID=0\nJob=\n",
        ), patch.object(control.DROP_IN.__class__, "is_file", return_value=True):
            self.assertFalse(control.status()["job"])

    def test_validator_rejects_changed_identity_without_powering_controller(self):
        with patch(
            "sys.argv",
            [
                "control",
                "validate",
                "--adapter",
                "hci1",
                "--address",
                "AA:BB:CC:DD:EE:FF",
            ],
        ), patch.object(control.os, "geteuid", return_value=0), patch.object(
            control, "property_value", return_value="11:22:33:44:55:66"
        ), patch.object(
            control, "status", return_value=state()
        ), patch.object(
            control, "prepare"
        ) as prepare, patch.object(
            control, "mutation_lock"
        ) as lock, patch(
            "sys.stdout", new_callable=io.StringIO
        ) as output:
            self.assertEqual(control.main(), 1)
            self.assertEqual(
                json.loads(output.getvalue())["reason"], "identity_mismatch"
            )
            prepare.assert_not_called()
            lock.assert_not_called()

    def test_validator_can_run_while_the_caller_holds_mutation_lock(self):
        with patch(
            "sys.argv",
            [
                "control",
                "validate",
                "--adapter",
                "hci1",
                "--address",
                "AA:BB:CC:DD:EE:FF",
            ],
        ), patch.object(control.os, "geteuid", return_value=0), patch.object(
            control, "property_value", return_value="AA:BB:CC:DD:EE:FF"
        ), patch.object(
            control, "prepare"
        ), patch.object(
            control, "status", return_value=state(job=True)
        ), patch.object(
            control, "mutation_lock"
        ) as lock, patch(
            "sys.stdout", new_callable=io.StringIO
        ):
            self.assertEqual(control.main(), 0)
            lock.assert_not_called()

    def test_apply_publishes_adapter_without_application_files(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "control/adapter.env"
            with patch.object(control, "PARAMETERS", path), patch.object(
                control, "status", return_value=state()
            ), patch.object(
                control, "prepare", return_value="AA:BB:CC:DD:EE:FF"
            ), patch.object(
                control, "run"
            ) as run:
                control.control("apply", "hci3")
                self.assertEqual(
                    path.read_text(),
                    "PILINK_ADAPTER=hci3\nPILINK_ADAPTER_ADDRESS=AA:BB:CC:DD:EE:FF\n",
                )
                commands = [call.args for call in run.call_args_list]
                self.assertIn(("systemctl", "start", control.UNIT), commands)
                self.assertFalse(any("rfkill" in command for command in commands))

    def test_preparation_failure_keeps_running_service_and_parameters(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.env"
            path.write_text("old parameters")
            with patch.object(control, "PARAMETERS", path), patch.object(
                control, "status", return_value=state(state="active")
            ), patch.object(
                control,
                "prepare",
                side_effect=control.ControlError("adapter_unavailable", "blocked"),
            ), patch.object(
                control, "run"
            ) as run:
                with self.assertRaises(control.ControlError):
                    control.control("apply", "hci1")
                run.assert_not_called()
                self.assertEqual(path.read_text(), "old parameters")

    def test_same_running_adapter_is_not_restarted(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.env"
            with patch.object(control, "PARAMETERS", path), patch.object(
                control, "status", return_value=state(state="active", adapter="hci1")
            ), patch.object(
                control, "prepare", return_value="AA:BB:CC:DD:EE:FF"
            ), patch.object(
                control, "run"
            ) as run:
                control.publish("hci1", "AA:BB:CC:DD:EE:FF")
                control.control("apply", "hci1")
                run.assert_called_once_with("systemctl", "enable", control.UNIT)

    def test_disable_removes_parameters_but_stop_keeps_them(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.env"
            path.write_text("parameters")
            with patch.object(control, "PARAMETERS", path), patch.object(
                control, "status", return_value=state()
            ), patch.object(control, "run"):
                control.control("stop")
                self.assertTrue(path.exists())
                control.control("disable")
                self.assertFalse(path.exists())

    def test_failed_stop_does_not_publish_new_parameters(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.env"
            path.write_text("old parameters")
            with patch.object(control, "PARAMETERS", path), patch.object(
                control, "status", return_value=state()
            ), patch.object(
                control, "prepare", return_value="AA:BB:CC:DD:EE:FF"
            ), patch.object(
                control, "run", side_effect=subprocess.TimeoutExpired("systemctl", 55)
            ):
                with self.assertRaises(subprocess.TimeoutExpired):
                    control.control("apply", "hci1")
                self.assertEqual(path.read_text(), "old parameters")

    def test_busy_or_standalone_service_is_not_changed(self):
        for values in (state(job=True), state(installed=False)):
            with patch.object(control, "status", return_value=values), patch.object(
                control, "run"
            ) as run:
                with self.assertRaises(control.ControlError):
                    control.control("disable")
                run.assert_not_called()

    def test_invalid_adapter_does_not_execute_command(self):
        with patch.object(control, "run") as run:
            with self.assertRaises(control.ControlError):
                control.prepare("hci1; reboot")
            run.assert_not_called()

    def test_rfkill_block_is_not_released(self):
        with patch.object(control.Path, "exists", return_value=True), patch.object(
            control,
            "run",
            return_value='{"rfkilldevices":[{"type":"bluetooth","device":"hci1","soft":"blocked","hard":"unblocked"}]}',
        ) as run:
            with self.assertRaises(control.ControlError):
                control.prepare("hci1")
            run.assert_called_once_with("rfkill", "--json")


if __name__ == "__main__":
    unittest.main()
