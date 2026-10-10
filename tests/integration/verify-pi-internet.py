#!/usr/bin/env python3
"""Verify Pi applications through an already-established BLE Internet relay."""

import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import subprocess
import time

REMOTE = r'''
import concurrent.futures, hashlib, json, os, pathlib, subprocess, sys, tempfile, time
proxy = 'socks5h://127.0.0.1:' + sys.argv[1]
wifi_interface = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != '-' else ''
results = []
environment = dict(os.environ)
for key in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'):
    environment.pop(key, None)
environment['ALL_PROXY'] = proxy
environment['GIT_TERMINAL_PROMPT'] = '0'

def run(command, **kwargs):
    result = subprocess.run(command, env=environment, capture_output=True, timeout=180, **kwargs)
    if result.returncode:
        raise RuntimeError(str(command[0]) + ': ' + result.stderr.decode(errors='replace'))
    return result

def record(name, action):
    started = time.monotonic()
    details = action()
    item = dict(case=name, status='PASS', seconds=round(time.monotonic()-started,3))
    if details: item.update(details)
    results.append(item)

def fetch(url):
    return run(['curl','--noproxy','','--fail','--silent','--show-error','--max-time','120',url]).stdout

def wifi_off_snapshot():
    radio = subprocess.check_output(['nmcli','radio','wifi'], text=True, timeout=10).strip()
    interfaces = json.loads(subprocess.check_output(['ip','-j','address','show','dev',wifi_interface], text=True, timeout=10))
    addresses = [address['local'] for link in interfaces for address in link.get('addr_info',[]) if address.get('scope') == 'global']
    routes4 = json.loads(subprocess.check_output(['ip','-j','-4','route','show','default'], text=True, timeout=10))
    routes6 = json.loads(subprocess.check_output(['ip','-j','-6','route','show','default'], text=True, timeout=10))
    assert radio == 'disabled', 'Pi Wi-Fi radio is enabled'
    assert not addresses, 'Pi Wi-Fi still has a global address'
    assert not routes4 and not routes6, 'Pi still has a default IP route'
    return dict(interface=wifi_interface, wifi_radio=radio, link_state=interfaces[0]['operstate'],
                global_addresses=addresses, ipv4_default_routes=routes4, ipv6_default_routes=routes6)

if wifi_interface:
    record('pi_wifi_disabled_before_transfer', wifi_off_snapshot)
    def direct_unavailable():
        direct_environment = dict(environment)
        direct_environment.pop('ALL_PROXY', None)
        result = subprocess.run(['curl','--proxy','','--noproxy','*','--silent','--show-error',
                                 '--connect-timeout','5','--max-time','8','https://example.com/'],
                                env=direct_environment, capture_output=True, timeout=12)
        assert result.returncode != 0 and not result.stdout, 'Direct Internet remains available on Pi'
        return dict(curl_exit_code=result.returncode)
    record('pi_direct_https_unavailable_without_wifi', direct_unavailable)

def https():
    body = fetch('https://example.com/')
    assert b'<title>Example Domain</title>' in body
    return dict(bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
record('pi_all_proxy_https_remote_dns', https)

def parallel():
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        pages=list(pool.map(fetch,('https://example.com/','https://www.rfc-editor.org/rfc/rfc1928.txt')))
    assert b'Example Domain' in pages[0] and b'SOCKS Protocol Version 5' in pages[1]
    return dict(bytes=[len(page) for page in pages])
record('two_parallel_https_requests_while_ble_ssh_held', parallel)

with tempfile.TemporaryDirectory(prefix='rpi-ble-tunnel-internet-test-') as temp:
    root=pathlib.Path(temp)
    def clone():
        path=root/'git'
        run(['git','-c','http.proxy='+proxy,'-c','http.sslVerify=true','-c','credential.helper=',
             'clone','--depth','1','--single-branch','--no-tags','https://github.com/octocat/Hello-World.git',str(path)])
        revision=run(['git','-C',str(path),'rev-parse','HEAD']).stdout.decode().strip()
        assert len(revision) in (40,64) and (path/'README').is_file()
        run(['git','-C',str(path),'fsck','--no-reflogs'])
        return dict(revision=revision, readme_bytes=(path/'README').stat().st_size)
    record('pi_git_https_shallow_clone', clone)

    def apt():
        directory=root/'apt'
        directory.mkdir()
        run(['apt-get','-o','Acquire::http::Proxy='+proxy,'-o','Acquire::https::Proxy='+proxy,
             '-o','Acquire::Retries=0','-o','Acquire::http::Timeout=60','download','hello'],cwd=directory)
        packages=list(directory.glob('hello_*.deb'))
        assert len(packages)==1
        archive=packages[0]
        assert run(['dpkg-deb','--field',str(archive),'Package']).stdout.strip()==b'hello'
        checksum=hashlib.sha256(archive.read_bytes()).hexdigest()
        metadata=run(['apt-cache','show','hello']).stdout.decode()
        assert 'SHA256: '+checksum in metadata, 'APT archive differs from local repository metadata'
        return dict(package=archive.name,bytes=archive.stat().st_size,sha256=checksum,installed=False)
    record('pi_apt_download_and_repository_checksum', apt)

def dns_error():
    result=subprocess.run(['curl','--noproxy','','--silent','--show-error','--max-time','30',
                           'https://rpi-ble-tunnel-test.invalid/'],env=environment,capture_output=True,timeout=40)
    assert result.returncode!=0 and not result.stdout
    assert b'Example Domain' in fetch('https://example.com/')
    return dict(curl_exit_code=result.returncode)
record('remote_dns_failure_preserves_next_https_request', dns_error)
if wifi_interface:
    record('pi_wifi_remains_disabled_after_transfer', wifi_off_snapshot)
print(json.dumps(dict(results=results)),flush=True)
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--target-port", type=int, default=22)
    parser.add_argument("--host-key-alias", required=True)
    parser.add_argument("--socks-port", type=int, default=1080)
    parser.add_argument("--ssh-port", type=int, default=2222)
    parser.add_argument("--central", choices=("macos", "android"), default="macos")
    parser.add_argument("--require-wifi-off", metavar="INTERFACE")
    parser.add_argument("--output", type=Path, default=Path("build/ble-internet-results.json"))
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_.@:-]+", args.target) or args.target.startswith("-"):
        parser.error("Invalid SSH target")
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+", args.host_key_alias) or args.host_key_alias.startswith("-"):
        parser.error("Invalid host key alias")
    if not all(1 <= port <= 65535 for port in (args.socks_port, args.ssh_port, args.target_port)):
        parser.error("Ports must be in 1..65535")
    if args.require_wifi_off and not re.fullmatch(r"[a-zA-Z0-9_.-]+", args.require_wifi_off):
        parser.error("Invalid wireless interface")
    options = ["-o", "BatchMode=yes", "-o", "HostKeyAlias=" + args.host_key_alias,
               "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=60",
               "-o", "ControlMaster=no", "-o", "ControlPath=none"]
    console = subprocess.Popen(["ssh", *options, "-p", str(args.ssh_port), "pi@127.0.0.1",
                                "printf 'CONSOLE_READY\\n'; read release; printf CONCURRENT_SSH_OK"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    started = time.monotonic()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            ready = pool.submit(console.stdout.readline)
            try:
                assert ready.result(timeout=60) == b"CONSOLE_READY\n", "BLE SSH console did not become ready"
            except BaseException:
                console.kill()
                raise
        # The control connection may itself use BLE when the Pi has no IP network.
        result = subprocess.run(["ssh", *options, "-p", str(args.target_port), args.target, "python3", "-",
                                 str(args.socks_port), args.require_wifi_off or "-"],
                                input=REMOTE.encode(), capture_output=True, timeout=900)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace"))
        report = json.loads(result.stdout)
        assert console.poll() is None, "BLE SSH console disconnected during Internet tests"
        output, error = console.communicate(input=b"release\n", timeout=60)
        assert console.returncode == 0 and output == b"CONCURRENT_SSH_OK", error.decode(errors="replace")
        report["results"].append({"case": "ble_ssh_console_survives_all_internet_tests", "status": "PASS",
                                  "seconds": round(time.monotonic() - started, 3)})
        report.update(transport="Pi loopback SOCKS5 / BLE L2CAP / " + ("Android" if args.central == "android" else "Mac") + " network", management=args.target,
                      management_port=args.target_port, socks_port=args.socks_port, termux_operated=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        for case in report["results"]:
            print(json.dumps(case), flush=True)
    finally:
        if console.poll() is None:
            console.kill()
        console.communicate(timeout=10)


if __name__ == "__main__":
    main()
