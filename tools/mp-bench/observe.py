#!/usr/bin/env python3
"""Debugging aid for tools/mp-bench: join the local server as one more player and
count what the server sends it -- player spawns, destroys, movement -- so a scene
that a client does not see can be blamed on the server or on the client.

  python tools/mp-bench/observe.py [--seconds 30] [--name Observer]
"""

import argparse
import collections
import os
import selectors
import sys
import time
import zlib

sys.path.insert(0, os.path.dirname(__file__))
import mpbench  # noqa: E402


class Observer(mpbench.Bot):
    def __init__(self, name):
        mpbench.Bot.__init__(self, name, 99)
        self.counts = collections.Counter()

    def handle(self, frame, entity_names):
        if self.play and self.threshold >= 0:
            data_length, at = mpbench.read_varint(frame, 0)
            if data_length != 0:
                payload = zlib.decompress(bytes(frame[at:]))
                packet_id, _ = mpbench.read_varint(payload, 0)
                self.counts[packet_id] += 1
                return
            packet_id, _ = mpbench.read_varint(frame, at)
            self.counts[packet_id] += 1
        mpbench.Bot.handle(self, frame, entity_names)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=float, default=30.0)
    parser.add_argument("--name", default="Observer")
    args = parser.parse_args()
    bot = Observer(args.name)
    bot.x, bot.y, bot.z = mpbench.CAMERA
    bot.connect()
    selector = selectors.DefaultSelector()
    selector.register(bot.sock, selectors.EVENT_READ, bot)
    names = {}
    started = time.time()
    last = started
    while time.time() - started < args.seconds:
        for key, _ in selector.select(0.05):
            chunk = bot.sock.recv(1 << 16)
            if not chunk:
                print("disconnected")
                return
            bot.feed(chunk, names)
        if time.time() - last >= 5.0:
            last = time.time()
            c = bot.counts
            print("t=%3.0fs spawnPlayer=%d destroy=%d move=%d relmove=%d chunkBulk=%d chunk=%d at (%.1f,%.1f,%.1f)" % (
                last - started, c[0x0C], c[0x13], c[0x18], c[0x15] + c[0x16] + c[0x17], c[0x26], c[0x21],
                bot.x, bot.y, bot.z))
            sys.stdout.flush()


if __name__ == "__main__":
    main()
