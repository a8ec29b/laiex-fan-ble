#!/usr/bin/env python3
"""Local BLE control for laiex.fan.lesfs through BlueZ."""
import argparse
from collections import deque
import hashlib
import hmac
import json
from pathlib import Path
import secrets
import struct
import sys
import time

import dbus
import dbus.mainloop.glib
from gi.repository import GLib
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESCCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

BASE = '-0000-1000-8000-00805f9b34fb'
PROPERTIES = {'fan': (1, bool), 'speed': (2, int), 'light': (3, bool),
              'brightness': (4, int), 'battery': (6, int)}
READY = bytes.fromhex('00000101')
ACK = bytes.fromhex('00000100')


class FanBLE:
    """One authenticated connection; commands run sequentially on its owner thread."""

    def __init__(self, credentials, mac, adapter='hci0'):
        self.token = bytes.fromhex(json.loads(Path(credentials).read_text())['token'])
        if len(self.token) != 12:
            raise ValueError('Device token must contain 12 bytes')
        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        self.bus = dbus.SystemBus()
        self.manager = dbus.Interface(self.bus.get_object('org.bluez', '/'),
                                      'org.freedesktop.DBus.ObjectManager')
        self.adapter_path = '/org/bluez/' + adapter
        self.path = self.adapter_path + '/dev_' + mac.upper().replace(':', '_')
        self.adapter = dbus.Interface(self.bus.get_object('org.bluez', self.adapter_path),
                                      'org.bluez.Adapter1')
        self.context = GLib.MainContext.default()
        self.queues = {}
        self.subscribed = []
        self.device = None
        self.discovering = False
        self.signal = self.bus.add_signal_receiver(
            self._changed, dbus_interface='org.freedesktop.DBus.Properties',
            signal_name='PropertiesChanged', path_keyword='path')
        self.send_counter = 0
        self.recv_counter = 0
        self.sequence = 1
        self.session = {}
        self.events = deque()

    def _changed(self, interface, props, invalid, path=None):
        if interface == 'org.bluez.GattCharacteristic1' and 'Value' in props:
            if path in self.queues:
                self.queues[path].append(bytes(props['Value']))

    def _until(self, predicate, timeout, message):
        deadline = time.monotonic() + timeout
        while True:
            while self.context.pending():
                self.context.iteration(False)
            value = predicate()
            if value:
                return value
            if time.monotonic() >= deadline:
                raise TimeoutError(message)
            time.sleep(0.01)

    def _receive(self, path, timeout=8):
        return self._until(lambda: self.queues[path].popleft() if self.queues[path] else None,
                           timeout, 'Timed out waiting for BLE notification')

    def _characteristic(self, path):
        return dbus.Interface(self.bus.get_object('org.bluez', path),
                              'org.bluez.GattCharacteristic1')

    def _write(self, path, payload):
        self._characteristic(path).WriteValue(
            dbus.Array(payload, signature='y'), {'type': 'command'}, timeout=5)

    def _subscribe(self, path):
        self.queues[path] = deque()
        self._characteristic(path).StartNotify(timeout=5)
        self.subscribed.append(path)

    def _send(self, path, kind, payload):
        count = (len(payload) + 17) // 18
        self._write(path, b'\0\0\0' + bytes([kind]) + struct.pack('<H', count))
        if self._receive(path) != READY:
            raise RuntimeError('BLE transport did not accept transfer')
        for offset in range(0, len(payload), 18):
            self._write(path, struct.pack('<H', offset // 18 + 1) + payload[offset:offset + 18])
        if self._receive(path) != ACK:
            raise RuntimeError('BLE transport did not acknowledge transfer')

    def _receive_packet(self, path):
        header = self._receive(path)
        if len(header) >= 4 and header[:3] == b'\0\0\x02':
            self._write(path, bytes.fromhex('00000300'))
            return header[3], header[4:]
        if len(header) != 6 or header[:3] != bytes(3):
            raise RuntimeError('Unexpected BLE transfer header: ' + header.hex())
        count = int.from_bytes(header[4:6], 'little')
        self._write(path, READY)
        parts = {}
        for _ in range(count):
            frame = self._receive(path)
            parts[int.from_bytes(frame[:2], 'little')] = frame[2:]
        payload = b''.join(parts[i] for i in range(1, count + 1))
        self._write(path, ACK)
        return header[3], payload

    def connect(self):
        self.adapter.SetDiscoveryFilter({'Transport': dbus.String('le'), 'Pattern': dbus.String('')})
        self.adapter.StartDiscovery()
        self.discovering = True
        self._until(lambda: self.path in self.manager.GetManagedObjects(), 25,
                    'Fan was not discovered; keep it powered and disconnect phone Bluetooth')
        self.device = dbus.Interface(self.bus.get_object('org.bluez', self.path), 'org.bluez.Device1')
        if not self.manager.GetManagedObjects()[self.path]['org.bluez.Device1']['Connected']:
            self.device.Connect(timeout=25)
        self._until(lambda: self.manager.GetManagedObjects().get(self.path, {}).get(
            'org.bluez.Device1', {}).get('ServicesResolved'), 20, 'GATT service discovery timed out')
        chars = {str(info['org.bluez.GattCharacteristic1']['UUID']): str(path)
                 for path, info in self.manager.GetManagedObjects().items()
                 if str(path).startswith(self.path) and 'org.bluez.GattCharacteristic1' in info}
        self.ctrl = chars['00000010' + BASE]
        self.auth_data = chars['00000019' + BASE]
        self.tx = chars['0000001a' + BASE]
        self.rx = chars['0000001b' + BASE]
        for path in (self.ctrl, self.auth_data, self.tx, self.rx):
            self._subscribe(path)
        self.adapter.StopDiscovery()
        self.discovering = False
        self._login()
        return self

    def _login(self):
        client_random = secrets.token_bytes(16)
        self._write(self.ctrl, bytes.fromhex('24000000'))
        self._send(self.auth_data, 11, client_random)
        kind, device_random = self._receive_packet(self.auth_data)
        if kind != 13 or len(device_random) != 16:
            raise RuntimeError('Unexpected device login challenge')
        derived = HKDF(algorithm=hashes.SHA256(), length=64,
                       salt=client_random + device_random,
                       info=b'mible-login-info').derive(self.token)
        self.session = {'dev_key': derived[:16], 'app_key': derived[16:32],
                        'dev_iv': derived[32:36], 'app_iv': derived[36:40]}
        kind, proof = self._receive_packet(self.auth_data)
        expected = hmac.new(self.session['dev_key'], device_random + client_random,
                            hashlib.sha256).digest()
        if kind != 12 or not hmac.compare_digest(proof, expected):
            raise RuntimeError('Device authentication failed; check the device token')
        self._send(self.auth_data, 10, hmac.new(self.session['app_key'],
                   client_random + device_random, hashlib.sha256).digest())
        if self._receive(self.ctrl) != bytes.fromhex('21000000'):
            raise RuntimeError('Device rejected login')

    def _encrypt(self, payload):
        counter = self.send_counter
        nonce = self.session['app_iv'] + bytes(4) + struct.pack('<I', counter)
        encrypted = AESCCM(self.session['app_key'], tag_length=4).encrypt(nonce, payload, None)
        self.send_counter += 1
        return struct.pack('<H', counter & 0xffff) + encrypted

    def _decrypt(self, payload):
        low = int.from_bytes(payload[:2], 'little')
        high = self.recv_counter & 0xffff0000
        if self.recv_counter & 0xffff > 0x7fff and low < 0x8000:
            high += 0x10000
        counter = high | low
        nonce = self.session['dev_iv'] + bytes(4) + struct.pack('<I', counter)
        plain = AESCCM(self.session['dev_key'], tag_length=4).decrypt(nonce, payload[2:], None)
        self.recv_counter = counter
        return plain

    @staticmethod
    def _decode_value(plain, offset):
        size, type_code = plain[offset:offset + 2]
        raw = plain[offset + 2:offset + 2 + size]
        if len(raw) != size:
            raise RuntimeError('Truncated MIoT value')
        if type_code == 0 and size == 1:
            return bool(raw[0])
        if type_code == 0x50 and size == 4:
            return int.from_bytes(raw, 'little')
        raise RuntimeError(f'Unsupported MIoT value type {type_code:#x}, length {size}')

    def _request(self, piid, value=None):
        sequence = self.sequence
        self.sequence = (sequence + 1) & 0xffff
        # GET body has no value. SET uses the property's actual wire type.
        body = bytes([2 if value is None else 0, 1, 2]) + struct.pack('<H', piid)
        if value is not None:
            if isinstance(value, bool):
                body += bytes([1, 0, int(value)])
            else:
                body += bytes([4, 0x50]) + struct.pack('<I', value)
        plain = bytes([4 + len(body), 0x20]) + struct.pack('<H', sequence) + body
        self._send(self.tx, 0, self._encrypt(plain))
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            kind, packet = self._receive_packet(self.rx)
            response = self._decrypt(packet)
            self.events.append(response.hex())
            if len(response) < 11 or response[1] != 0x20:
                raise RuntimeError('Unexpected MIoT response')
            opcode = response[4]
            response_sequence = int.from_bytes(response[2:4], 'little')
            if (opcode == (3 if value is None else 1) and response_sequence == sequence
                    and response[6] == 2 and int.from_bytes(response[7:9], 'little') == piid):
                code = int.from_bytes(response[9:11], 'little', signed=True)
                if code:
                    raise RuntimeError(f'MIoT property {piid} failed: {code}')
                return self._decode_value(response, 11) if value is None else None
        raise TimeoutError('No matching MIoT property response')

    def get(self, name):
        return self._request(PROPERTIES[name][0])

    def status(self):
        return {name: self.get(name) for name in PROPERTIES}

    def set(self, name, value):
        if name == 'battery':
            raise ValueError('Battery is read-only')
        piid, value_type = PROPERTIES[name]
        if value_type is bool and type(value) is not bool:
            raise ValueError('Switch value must be bool')
        if name in ('speed', 'brightness'):
            upper = 5 if name == 'speed' else 3
            if type(value) is not int or not 1 <= value <= upper:
                raise ValueError(f'{name} must be an integer from 1 to {upper}')
        self._request(piid, value)
        actual = self.get(name)
        if actual != value:
            raise RuntimeError(f'{name} readback was {actual}, expected {value}')
        return actual

    def close(self):
        for path in reversed(self.subscribed):
            try:
                self._characteristic(path).StopNotify(timeout=5)
            except dbus.DBusException:
                pass
        self.subscribed.clear()
        if self.device:
            try:
                self.device.Disconnect(timeout=10)
            except dbus.DBusException:
                pass
        if self.discovering:
            self.adapter.StopDiscovery()
            self.discovering = False
        self.signal.remove()

    def __enter__(self):
        try:
            return self.connect()
        except Exception:
            self.close()
            raise

    def __exit__(self, *args):
        self.close()


def main():
    parser = argparse.ArgumentParser(description='Control laiex.fan.lesfs locally over BLE')
    parser.add_argument('--credentials', required=True, help='JSON file containing the device token')
    parser.add_argument('--mac', required=True, help='Bluetooth address of the fan')
    parser.add_argument('--adapter', default='hci0')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('status')
    setter = commands.add_parser('set')
    setter.add_argument('property', choices=['fan', 'speed', 'light', 'brightness'])
    setter.add_argument('value')
    args = parser.parse_args()
    value = None
    if args.command == 'set':
        if args.property in ('fan', 'light'):
            if args.value not in ('on', 'off'):
                parser.error('Switch value must be on or off')
            value = args.value == 'on'
        else:
            try:
                value = int(args.value)
            except ValueError:
                parser.error('Level must be an integer')
            upper = 5 if args.property == 'speed' else 3
            if not 1 <= value <= upper:
                parser.error(f'{args.property} must be from 1 to {upper}')
    try:
        with FanBLE(args.credentials, args.mac, args.adapter) as fan:
            result = fan.status() if args.command == 'status' else {
                args.property: fan.set(args.property, value)}
        print(json.dumps(result, ensure_ascii=False))
    except (dbus.DBusException, RuntimeError, TimeoutError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
