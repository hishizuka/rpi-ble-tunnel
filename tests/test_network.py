"""Verify notification-driven network supervision without changing host networking."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("rpi_ble_tunnel_network", Path(__file__).resolve().parents[1] / "pi/rpi-ble-tunnel-network.py")
network = importlib.util.module_from_spec(spec)
spec.loader.exec_module(network)


class ScriptedEvents:
    def __init__(self, actions):
        self.actions = iter(actions)
        self.timeouts = []
        self.watched = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def watch_process(self, tag, pid):
        self.watched.append((tag, pid))

    def wait(self, timeout=None):
        self.timeouts.append(timeout)
        next(self.actions)()
        return set()


class SupervisionTests(unittest.TestCase):
    def make_network(self):
        stop = network.StopEvent()
        instance = network.Network(Path("/unused/link.json"), Path("/unused"), Path("/unused/hev"), stop)
        instance.status = Mock()

        def cleanup():
            instance.session = instance.engine = None

        instance.cleanup = Mock(side_effect=cleanup)
        return instance, stop

    def test_idle_connect_disconnect_wait_only_for_notifications(self):
        instance, stop = self.make_network()
        link = {"pid": 123, "start_ticks": 456, "session": "abc", "socks_port": 1080}
        current = [None]
        instance.healthy = Mock(return_value=True)

        def start(value):
            instance.session = value
            instance.engine = Mock(pid=321)

        instance.start = Mock(side_effect=start)
        events = ScriptedEvents([lambda: current.__setitem__(0, link), lambda: None,
                                 lambda: current.__setitem__(0, None), stop.set])
        with patch.object(network, "NetworkEvents", return_value=events), patch.object(
                network, "read_link", side_effect=lambda _: current[0]):
            instance.serve()
        self.assertEqual(events.timeouts, [None] * 4)
        instance.start.assert_called_once_with(link)
        instance.healthy.assert_called_once()
        self.assertEqual(instance.cleanup.call_count, 3)
        self.assertIn(("link", 123), events.watched)
        self.assertIn(("engine", 321), events.watched)

    def test_failed_setup_waits_for_retry_deadline(self):
        instance, stop = self.make_network()
        instance.start = Mock(side_effect=RuntimeError("setup failed"))
        events = ScriptedEvents([stop.set])
        with patch.object(network, "NetworkEvents", return_value=events), patch.object(
                network, "read_link", return_value={"pid": 123}), self.assertLogs(level="ERROR"):
            instance.serve()
        self.assertEqual(len(events.timeouts), 1)
        self.assertGreater(events.timeouts[0], 4.5)
        self.assertLessEqual(events.timeouts[0], 5)
        instance.start.assert_called_once()

    def test_daemon_exit_wakes_supervisor_and_cleans_up(self):
        instance, stop = self.make_network()
        daemon = Mock(pid=123, returncode=7)
        daemon.poll.return_value = None
        events = ScriptedEvents([lambda: setattr(daemon.poll, "return_value", 7)])
        with patch.object(network, "NetworkEvents", return_value=events), patch.object(
                network, "read_link", return_value=None), self.assertRaisesRegex(RuntimeError, "status 7"):
            instance.serve(daemon)
        self.assertEqual(events.timeouts, [None])
        self.assertEqual(instance.cleanup.call_count, 2)

    def test_engine_readiness_uses_events_and_a_single_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = network.Network(Path(directory) / "link.json", Path(directory), Path("/unused/hev"), network.StopEvent())
            instance.events = Mock()
            instance.current = Mock()
            instance.profile = Mock(return_value=False)
            instance.remember_interface = Mock()
            instance.nm = Mock()
            instance.status = Mock()
            engine = Mock(pid=321)
            engine.poll.return_value = None
            replies = [Mock(returncode=1), Mock(stdout=json.dumps([{"flags": []}])),
                       Mock(stdout=json.dumps([{"flags": ["LOWER_UP"]}]))]
            with patch.object(network, "run_command", side_effect=replies), patch.object(
                    network.subprocess, "Popen", return_value=engine):
                instance.start({"socks_port": 1080, "session": "abc"})
            instance.events.watch_process.assert_called_once_with("engine", 321)
            instance.events.wait.assert_called_once()
            self.assertGreater(instance.events.wait.call_args.args[0], 0)
            self.assertLessEqual(instance.events.wait.call_args.args[0], 5)
            self.assertEqual(instance.session["session"], "abc")


class ProcessStopTests(unittest.TestCase):
    def test_running_process_is_stopped_and_reaped(self):
        with subprocess.Popen([sys.executable, "-c", "import signal; print('ready', flush=True); signal.pause()"],
                              stdout=subprocess.PIPE) as process:
            self.assertEqual(process.stdout.readline(), b"ready\n")
            network.stop_process(process)
            self.assertEqual(process.returncode, -signal.SIGTERM)

    def test_unresponsive_process_is_killed_and_reaped(self):
        code = "import signal; signal.signal(signal.SIGTERM, signal.SIG_IGN); print('ready', flush=True); signal.pause()"
        with subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE) as process:
            self.assertEqual(process.stdout.readline(), b"ready\n")
            network.stop_process(process)
            self.assertEqual(process.returncode, -signal.SIGKILL)

    def test_already_exited_process_keeps_its_exit_status(self):
        with subprocess.Popen([sys.executable, "-c", "raise SystemExit(7)"]) as process:
            process.wait(timeout=3)
            network.stop_process(process)
            self.assertEqual(process.returncode, 7)


@unittest.skipUnless(sys.platform == "linux" and hasattr(os, "pidfd_open"), "Linux notification APIs")
class LinuxEventsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        monitor = self.root / "nmcli"
        monitor.write_text("#!/usr/bin/env python3\nimport signal\nsignal.pause()\n")
        monitor.chmod(0o755)
        self.environment = patch.dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ["PATH"])
        self.environment.start()
        self.stop = network.StopEvent()
        self.events = network.NetworkEvents(self.root / "link.json", self.stop)
        # Host link notifications are unrelated to these isolated file/process tests.
        self.events.selector.unregister(self.events.route)

    def tearDown(self):
        self.events.close()
        self.environment.stop()
        self.directory.cleanup()

    def test_idle_blocks_without_periodic_wakeup(self):
        timer = threading.Timer(0.12, self.stop.set)
        timer.start()
        try:
            started = time.monotonic()
            self.assertEqual(self.events.wait(), {"stop"})
            self.assertGreater(time.monotonic() - started, 0.09)
        finally:
            timer.join()

    def test_atomic_replacement_and_deletion_notify(self):
        temporary = self.root / "new.json"
        temporary.write_text("{}")
        temporary.replace(self.root / "link.json")
        self.assertIn("link", self.events.wait(1))
        (self.root / "link.json").unlink()
        self.assertIn("link", self.events.wait(1))

    def test_unrelated_file_does_not_report_link_change(self):
        (self.root / "other.json").write_text("{}")
        self.assertEqual(self.events.wait(1), set())
        self.assertEqual(self.events.wait(0), set())

    def test_process_exit_wakes_once_and_releases_pidfd(self):
        child = subprocess.Popen([sys.executable, "-c", "import sys; sys.stdin.read()"], stdin=subprocess.PIPE)
        try:
            self.events.watch_process("engine", child.pid)
            child.communicate(timeout=5)
            self.assertIn("engine", self.events.wait(1))
            self.assertNotIn("engine", self.events.processes)
            self.assertEqual(self.events.wait(0), set())
        finally:
            if child.poll() is None:
                child.kill()
            child.wait()

    def test_monitor_exit_restarts_only_at_retry_deadline(self):
        old = self.events.monitor
        old.kill()
        old.wait()
        self.assertIn("network", self.events.wait(1))
        self.assertIsNone(self.events.monitor)
        self.assertGreater(self.events.monitor_retry, time.monotonic())
        self.events.monitor_retry = time.monotonic() - 1
        self.assertIn("network", self.events.wait(0))
        self.assertIsNotNone(self.events.monitor)
        self.assertNotEqual(self.events.monitor.pid, old.pid)


if __name__ == "__main__":
    unittest.main()
