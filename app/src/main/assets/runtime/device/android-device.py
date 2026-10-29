#!/usr/bin/python3
"""User-enabled Android bridge. Commands never inherit shell/root privileges."""
import argparse
import base64
import json
from pathlib import Path
import socket
import struct
import sys


def main():
    parser = argparse.ArgumentParser(description='Android state, screen and input. Enable Device access in App permissions first.')
    commands = parser.add_subparsers(dest='operation', required=True)
    for name in ('info', 'app-status', 'tree'):
        commands.add_parser(name)
    screen = commands.add_parser('screenshot')
    screen.add_argument('output', type=Path, help='JPEG output path')
    tap = commands.add_parser('tap')
    tap.add_argument('x', type=int)
    tap.add_argument('y', type=int)
    swipe = commands.add_parser('swipe')
    for name in ('x1', 'y1', 'x2', 'y2'):
        swipe.add_argument(name, type=int)
    swipe.add_argument('--duration', type=int, default=300)
    text = commands.add_parser('input', help='Insert text into the focused field; Unicode requires the optional accessibility service')
    text.add_argument('text', nargs='?', help='Text to insert. Omit to read from stdin.')
    key = commands.add_parser('key')
    key.add_argument('key', choices=('back', 'home', 'recents', 'enter', 'tab', 'up', 'down', 'left', 'right', 'delete'))
    args = vars(parser.parse_args())
    operation = args.pop('operation')
    output = args.pop('output', None)
    if operation == 'input' and args['text'] is None:
        args['text'] = sys.stdin.read(2001)
    try:
        config = json.loads(Path('/manager/device-access/session.json').read_text())
    except (OSError, ValueError):
        parser.exit(1, 'Device access is disabled. Enable it in App > Settings > Permissions > Device access.\n')
    request = json.dumps(dict(token=config['token'], operation=operation, arguments=args)).encode()

    def receive(stream, size):
        result = bytearray()
        while len(result) < size:
            chunk = stream.recv(size - len(result))
            if not chunk:
                raise OSError('Session ended; enable Device access again if necessary')
            result.extend(chunk)
        return result

    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stream:
            stream.settimeout(40)
            stream.connect('\0' + config['socket'])
            stream.sendall(struct.pack('!I', len(request)) + request)
            size, = struct.unpack('!I', receive(stream, 4))
            if size > 700000:
                raise ValueError('Response exceeds size limit')
            response = json.loads(receive(stream, size))
        if not response.get('ok'):
            parser.exit(1, response.get('error', 'Request refused') + '\n')
        result = response['result']
        if output:
            output.write_bytes(base64.b64decode(result.pop('data'), validate=True))
            result['output'] = str(output)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, 'Android bridge: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
