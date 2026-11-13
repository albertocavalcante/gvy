#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
exec python3 -I - "$@" <<'PY'
import base64
import hashlib
import json
import os
import pathlib
import resource
import stat
import subprocess
import sys
import tempfile

LIMIT = 256 * 1024
OUTPUT_LIMIT = 2 * 1024 * 1024
ENVELOPE_LIMIT = 1024 * 1024
RECIPIENT = 'age1f6xat00673xzh46k0hnyfa5eja9njkx4r794qvxuffypmf9qdddsz5dcd7'
AGE = pathlib.Path(os.environ['RUNNER_TEMP']) / 'age-bin' / 'age'
KEYGEN = AGE.with_name('age-keygen')

def cap_output():
    resource.setrlimit(resource.RLIMIT_FSIZE, (OUTPUT_LIMIT, OUTPUT_LIMIT))

def read_fixed(path, dir_fd=None):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=dir_fd)
    except FileNotFoundError:
        return {'state': 'missing'}
    except OSError:
        return {'state': 'unreadable'}
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            return {'state': 'not_regular'}
        if before.st_size > LIMIT:
            return {'state': 'oversize', 'length': before.st_size}
        data = stream.read(LIMIT + 1)
        after = os.fstat(stream.fileno())
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or len(data) != before.st_size:
        return {'state': 'changed'}
    return {'state': 'present', 'length': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'base64': base64.b64encode(data).decode('ascii')}

def read_test_log(module, target):
    root_fd = None
    try:
        configured_base = pathlib.Path(os.environ['BAZEL_PRIVATE_OUTPUT_BASE'])
        if not configured_base.is_absolute():
            return {'state': 'untrusted_or_missing_parent'}
        output_base = configured_base.resolve(strict=True)
        testlogs = pathlib.Path('bazel-testlogs').resolve(strict=True)
        relative = testlogs.relative_to(output_base)
        root_fd = os.open(output_base, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in (*relative.parts, module, target):
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
            os.close(root_fd)
            root_fd = next_fd
        return read_fixed('test.log', dir_fd=root_fd)
    except (KeyError, OSError, ValueError):
        return {'state': 'untrusted_or_missing_parent'}
    finally:
        if root_fd is not None:
            os.close(root_fd)

def encrypt(payload, recipient, dest, program=AGE):
    if len(payload) > ENVELOPE_LIMIT:
        return False
    temp = dest.with_name(dest.name + '.partial')
    try:
        with temp.open('xb') as output:
            result = subprocess.run([str(program), '-r', recipient], input=payload, stdout=output, stderr=subprocess.DEVNULL, timeout=30, preexec_fn=cap_output, check=False)
        if result.returncode != 0 or not 0 < temp.stat().st_size <= OUTPUT_LIMIT:
            return False
        os.link(temp, dest)
        return True
    except (OSError, subprocess.TimeoutExpired):
        return False
    finally:
        temp.unlink(missing_ok=True)

def controls():
    with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP'], prefix='age-control-') as directory:
        root = pathlib.Path(directory)
        key = root / 'key'
        other = root / 'other'
        for path in (key, other):
            subprocess.run([str(KEYGEN), '-o', str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=True)
        recipient = subprocess.run([str(KEYGEN), '-y', str(key)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15, check=True).stdout.decode().strip()
        payload = b'fixed private diagnostic control\n'
        cipher = root / 'control.age'
        assert encrypt(payload, recipient, cipher)
        def decrypt(identity, data):
            return subprocess.run([str(AGE), '-d', '-i', str(identity)], input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=15, check=False)
        raw = cipher.read_bytes()
        result = decrypt(key, raw)
        assert result.returncode == 0 and result.stdout == payload
        assert decrypt(other, raw).returncode != 0
        corrupt = bytearray(raw)
        corrupt[-1] ^= 1
        assert decrypt(key, bytes(corrupt)).returncode != 0
        assert not encrypt(b'x' * (ENVELOPE_LIMIT + 1), recipient, root / 'oversize.age')
        assert not encrypt(payload, recipient, root / 'failed.age', root / 'missing-age')
        assert not (root / 'oversize.age').exists() and not (root / 'failed.age').exists()
        huge = root / 'huge'
        huge.write_bytes(b'x' * (LIMIT + 1))
        assert read_fixed(huge)['state'] == 'oversize'
        link = root / 'link'
        link.symlink_to(huge)
        assert read_fixed(link)['state'] == 'unreadable'
        absent = {name: read_fixed(root / name) for name in ('absent-a', 'absent-b')}
        assert all(value['state'] == 'missing' for value in absent.values())
        absent_cipher = root / 'absent.age'
        assert encrypt(json.dumps(absent).encode(), recipient, absent_cipher)
        assert decrypt(key, absent_cipher.read_bytes()).returncode == 0
        workspace = root / 'workspace'
        output_base = root / 'output-base'
        testlogs = output_base / 'testlogs'
        target = testlogs / 'build-tool' / 'build-tool_test'
        target.mkdir(parents=True)
        workspace.mkdir()
        log = target / 'test.log'
        log.write_bytes(payload)
        alias = workspace / 'bazel-testlogs'
        alias.symlink_to(testlogs, target_is_directory=True)
        old_cwd = pathlib.Path.cwd()
        old_base = os.environ.get('BAZEL_PRIVATE_OUTPUT_BASE')
        try:
            os.chdir(workspace)
            os.environ['BAZEL_PRIVATE_OUTPUT_BASE'] = str(output_base)
            observed = read_test_log('build-tool', 'build-tool_test')
            assert observed['state'] == 'present'
            assert observed['length'] == len(payload)
            assert observed['sha256'] == hashlib.sha256(payload).hexdigest()
            assert base64.b64decode(observed['base64']) == payload
            assert read_test_log('jenkins', 'jenkins_test')['state'] == 'untrusted_or_missing_parent'
            alias.unlink()
            alias.symlink_to(root, target_is_directory=True)
            assert read_test_log('build-tool', 'build-tool_test')['state'] == 'untrusted_or_missing_parent'
            alias.unlink()
            alias.symlink_to(testlogs, target_is_directory=True)
            module = testlogs / 'build-tool'
            module.rename(testlogs / 'real-module')
            module.symlink_to(testlogs / 'real-module', target_is_directory=True)
            assert read_test_log('build-tool', 'build-tool_test')['state'] == 'untrusted_or_missing_parent'
            module.unlink()
            (testlogs / 'real-module').rename(module)
            log.unlink()
            log.symlink_to(root / 'huge')
            assert read_test_log('build-tool', 'build-tool_test')['state'] == 'unreadable'
        finally:
            os.chdir(old_cwd)
            if old_base is None:
                os.environ.pop('BAZEL_PRIVATE_OUTPUT_BASE', None)
            else:
                os.environ['BAZEL_PRIVATE_OUTPUT_BASE'] = old_base

def seal(args):
    if len(args) != 8 or any(not value.isdecimal() for value in args[:6]):
        raise ValueError('invalid status')
    first, first_capture, second, second_capture, cleanup, stability, workflow, dest = args
    if len(workflow) != 40 or any(ch not in '0123456789abcdef' for ch in workflow):
        raise ValueError('invalid workflow')
    logs = {'build-tool': read_test_log('build-tool', 'build-tool_test'), 'jenkins': read_test_log('jenkins', 'jenkins_test')}
    body = {'schemaVersion': 1, 'kind': 'gvy-private-bazel-diagnostic', 'source': 'af8ec6f18d41426789ca3e802c9f70736964b310', 'tree': '5ad25abb4bbd37e8a84bcf65eb61fb2116b89c80', 'workflow': workflow, 'run': os.environ['GITHUB_RUN_ID'], 'attempt': os.environ['GITHUB_RUN_ATTEMPT'], 'status': {'build_tool': int(first), 'build_tool_capture': int(first_capture), 'jenkins': int(second), 'jenkins_capture': int(second_capture), 'cleanup': int(cleanup), 'stability': int(stability)}, 'logs': logs}
    payload = json.dumps(body, sort_keys=True, separators=(',', ':')).encode()
    if not encrypt(payload, RECIPIENT, pathlib.Path(dest)):
        raise RuntimeError('encryption failed')
    return all(item['state'] == 'present' for item in logs.values())

try:
    if sys.argv[1] == 'controls':
        controls()
    elif sys.argv[1] == 'seal':
        if not seal(sys.argv[2:]):
            raise RuntimeError('log unavailable')
    else:
        raise ValueError('invalid mode')
except Exception:
    print('private_diagnostic_failed', file=sys.stderr)
    sys.exit(1)
PY
