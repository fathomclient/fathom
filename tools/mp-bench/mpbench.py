#!/usr/bin/env python3
"""Fathom multiplayer profile: a local 1.8.9 server, scripted players, and the
scene handshake with the client's view benchmark.

WHY THIS EXISTS

The view benchmark (-Dfathom.benchmark=view) explains a singleplayer frame. A
multiplayer frame has things a singleplayer one does not -- other players with
armour and nametags, a server scoreboard, chat, packets arriving every tick --
and none of that can be reproduced by spawning client-side props: the packets
are the point. So this runs a real vanilla 1.8.9 dedicated server on
127.0.0.1, fills it with scripted players that speak protocol 47 over real
sockets, and sets up one scene at a time when the client asks for it.

WHAT IT DOES NOT DO

It never contacts anything but 127.0.0.1. The server runs in offline mode,
bound to the loopback address, so nothing outside this machine can join it and
no account is involved. The server jar is the one Loom already downloaded for
the module build; nothing is fetched. The Minecraft EULA has to be accepted for
the server to start: `prepare` refuses to write eula.txt unless it is run with
--accept-eula, so that is a decision made by a person, not by this script.

USAGE

  python tools/mp-bench/mpbench.py prepare --accept-eula
      Create run-mp/server, generate the world, build the arena, copy the world
      to run-mp/saves/mpbench for the singleplayer scene.
  python tools/mp-bench/mpbench.py serve [--bots 40]
      Start the server and the bots and answer the client's scene requests
      until it writes done.txt (or Ctrl+C).

The client side: run-mp is the game directory; see tools/mp-bench/run-client.ps1.
"""

import argparse
import hashlib
import math
import os
import random
import selectors
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RUN = os.path.join(ROOT, "minecraft", "fathom-optimizations", "run-mp")
SERVER_DIR = os.path.join(RUN, "server")
CONTROL = os.path.join(RUN, "control")
SAVE_NAME = "mpbench"
SERVER_JAR = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom", "1.8.9",
                          "minecraft-server.jar")
JAVA8 = r"C:\Program Files\Eclipse Adoptium\jdk-8.0.502.7-hotspot\bin\java.exe"
HOST = "127.0.0.1"
PORT = 25599
SEED = "-4172144997902289642"
BENCH_PLAYER = "FathomBench"

# The arena: a stone floor at Y=100 above the terrain, the camera at its
# north edge looking south (+Z) across it, the terrain below and beyond.
FLOOR_Y = 100
STAND_Y = FLOOR_Y + 1
CAMERA = (0.5, STAND_Y, -4.5)
CAMERA_YAW = 0.0
CAMERA_PITCH = 8.0
PARK = (176.5, STAND_Y, 0.5)

LOG = print


# ---------------------------------------------------------------------------
# Protocol 47 (1.8.x) primitives
# ---------------------------------------------------------------------------

def varint(value):
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(data, at):
    result = 0
    for shift in range(0, 35, 7):
        if at >= len(data):
            return None, at
        byte = data[at]
        at += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            if result & 0x80000000:
                result -= 1 << 32
            return result, at
    raise ValueError("varint too long")


def string(text):
    raw = text.encode("utf-8")
    return varint(len(raw)) + raw


def uuid_string(raw):
    text = raw.hex()
    return "%s-%s-%s-%s-%s" % (text[:8], text[8:12], text[12:16], text[16:20], text[20:])


def offline_uuid(name):
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return bytes(digest)


CHAT_LINES = [
    "gg", "anyone up for a duel", "lag?", "nice one", "who wants to party", "brb", "ez",
    "what map is next", "i need iron", "rush mid", "go go go", "wp", "lol", "same", "where is red",
    "bridge left", "someone has bow", "pots?", "trade emeralds", "1v1 me", "ok", "gl hf",
]


class Bot(object):
    """One scripted player. Reads only the handful of packets it must answer."""

    def __init__(self, name, index):
        self.name = name
        self.index = index
        self.uuid = offline_uuid(name)
        self.sock = None
        self.buffer = bytearray()
        self.read_at = 0
        self.play = False
        self.threshold = -1
        self.entity_id = None
        self.x, self.y, self.z = PARK
        self.yaw = 0.0
        self.pitch = 0.0
        self.alive = True
        self.behaviour = "park"
        self.anchor = PARK
        self.radius = 0.0
        self.phase = random.random() * math.tau
        self.speed = 0.0
        self.partner = None
        self.chat_rate = 0.0
        self.action_rate = 0.0
        self.sneaking = False
        self.sprinting = False
        self.tick_offset = random.randint(0, 9)
        self.teleported = False

    # -- connection ---------------------------------------------------------

    def connect(self):
        self.buffer = bytearray()
        self.read_at = 0
        self.play = False
        self.threshold = -1
        self.entity_id = None
        self.alive = True
        self.behaviour = "idle"
        self.chat_rate = 0.0
        self.action_rate = 0.0
        self.sock = socket.create_connection((HOST, PORT))
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        handshake = varint(47) + string(HOST) + struct.pack(">H", PORT) + varint(2)
        self.raw_send(0x00, handshake)
        self.raw_send(0x00, string(self.name))

    def raw_send(self, packet_id, payload):
        data = varint(packet_id) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                body = varint(len(data)) + zlib.compress(data)
            else:
                body = varint(0) + data
        else:
            body = data
        try:
            self.sock.sendall(varint(len(body)) + body)
        except OSError:
            self.alive = False

    def feed(self, chunk, entity_names):
        self.buffer += chunk
        while True:
            length, at = read_varint(self.buffer, self.read_at)
            if length is None or at + length > len(self.buffer):
                break
            frame = self.buffer[at:at + length]
            self.read_at = at + length
            self.handle(frame, entity_names)
        if self.read_at > 1 << 16:
            del self.buffer[:self.read_at]
            self.read_at = 0

    def handle(self, frame, entity_names):
        at = 0
        if self.threshold >= 0:
            data_length, at = read_varint(frame, 0)
            if data_length != 0:
                # Compressed: chunks, big metadata. Nothing a bot must answer.
                return
        packet_id, at = read_varint(frame, at)
        if not self.play:
            if packet_id == 0x03:
                self.threshold, _ = read_varint(frame, at)
            elif packet_id == 0x02:
                self.play = True
            elif packet_id == 0x00:
                LOG("[bot %s] refused at login: %s" % (self.name, bytes(frame[at:]).decode("utf-8", "replace")))
                self.alive = False
            return
        if packet_id == 0x00:
            keep, _ = read_varint(frame, at)
            self.raw_send(0x00, varint(keep))
        elif packet_id == 0x01:
            self.entity_id = struct.unpack(">i", bytes(frame[at:at + 4]))[0]
            settings = string("en_US") + struct.pack(">bbBB", 8, 0, 1, 0x7F)
            self.raw_send(0x15, settings)
        elif packet_id == 0x06:
            health = struct.unpack(">f", bytes(frame[at:at + 4]))[0]
            if health <= 0.0:
                self.raw_send(0x16, varint(0))
        elif packet_id == 0x08:
            x, y, z, yaw, pitch, flags = struct.unpack(">dddffb", bytes(frame[at:at + 33]))
            self.x = self.x + x if flags & 0x01 else x
            self.y = self.y + y if flags & 0x02 else y
            self.z = self.z + z if flags & 0x04 else z
            self.yaw = self.yaw + yaw if flags & 0x08 else yaw
            self.pitch = self.pitch + pitch if flags & 0x10 else pitch
            self.teleported = True
            self.send_position()
        elif packet_id == 0x0C:
            eid, at2 = read_varint(frame, at)
            uuid = bytes(frame[at2:at2 + 16])
            name = entity_names.get(uuid)
            if name is not None:
                Bots.ids[name] = eid
        elif packet_id == 0x40:
            LOG("[bot %s] disconnected: %s" % (self.name, bytes(frame[at:]).decode("utf-8", "replace")))
            self.alive = False

    # -- actions ------------------------------------------------------------

    def send_position(self):
        payload = struct.pack(">dddff?", self.x, self.y, self.z, self.yaw, self.pitch, True)
        self.raw_send(0x06, payload)

    def tick(self, tick):
        if self.sock is None or not self.play or not self.alive:
            return
        if self.behaviour in ("park", "idle"):
            if (tick + self.tick_offset) % 20 == 0:
                self.send_position()
            return
        if self.behaviour == "walk":
            self.phase += self.speed / max(self.radius, 0.5)
            nx = self.anchor[0] + math.cos(self.phase) * self.radius
            nz = self.anchor[2] + math.sin(self.phase) * self.radius
            self.yaw = math.degrees(math.atan2(-(nx - self.x), nz - self.z)) if (nx, nz) != (self.x, self.z) else self.yaw
            self.x, self.z = nx, nz
            self.y = STAND_Y
        elif self.behaviour == "stand":
            self.yaw = 180.0 + math.sin((tick + self.tick_offset * 7) / 23.0) * 25.0
            self.pitch = math.sin((tick + self.tick_offset * 5) / 31.0) * 10.0
        elif self.behaviour == "fight":
            self.phase += self.speed
            side = 1.0 if self.index % 2 == 0 else -1.0
            self.x = self.anchor[0] + math.cos(self.phase) * 1.3 * side
            self.z = self.anchor[2] + math.sin(self.phase) * 1.3 * side
            self.y = STAND_Y
            dx = self.anchor[0] - self.x
            dz = self.anchor[2] - self.z
            self.yaw = math.degrees(math.atan2(-dx, dz))
            self.pitch = 5.0
        self.send_position()
        if self.behaviour == "fight" and (tick + self.tick_offset) % 10 == 0:
            self.raw_send(0x0A, b"")
            target = Bots.ids.get(self.partner)
            if target is not None:
                self.raw_send(0x02, varint(target) + varint(1))
        if self.chat_rate and random.random() < self.chat_rate:
            self.raw_send(0x01, string(random.choice(CHAT_LINES)))
        if self.action_rate and self.entity_id is not None and random.random() < self.action_rate:
            if random.random() < 0.5:
                self.sneaking = not self.sneaking
                action = 0 if self.sneaking else 1
            else:
                self.sprinting = not self.sprinting
                action = 3 if self.sprinting else 4
            self.raw_send(0x0B, varint(self.entity_id) + varint(action) + varint(0))


class Bots(object):
    ids = {}


# ---------------------------------------------------------------------------
# The server
# ---------------------------------------------------------------------------

class Server(object):
    def __init__(self):
        self.process = None
        self.ready = threading.Event()
        self.log = open(os.path.join(RUN, "server-console.log"), "a", encoding="utf-8")
        self.lock = threading.Lock()

    def start(self):
        if not os.path.isfile(SERVER_JAR):
            raise SystemExit("No server jar at %s. Build the module once so Loom downloads it." % SERVER_JAR)
        jar = os.path.join(SERVER_DIR, "minecraft_server.1.8.9.jar")
        if not os.path.isfile(jar):
            shutil.copyfile(SERVER_JAR, jar)
        self.process = subprocess.Popen(
            [JAVA8, "-Xms1G", "-Xmx2G", "-XX:+UseG1GC", "-jar", jar, "nogui"],
            cwd=SERVER_DIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))
        threading.Thread(target=self.pump, daemon=True).start()
        if not self.ready.wait(300):
            raise SystemExit("The server did not finish starting in 300 s; see server-console.log")

    def pump(self):
        for raw in self.process.stdout:
            text = raw.decode("utf-8", "replace").rstrip()
            self.log.write(text + "\n")
            self.log.flush()
            if "Done (" in text:
                self.ready.set()
            if "Failed to bind" in text or "EULA" in text.upper() and "agree" in text.lower():
                LOG("[server] " + text)

    def command(self, text):
        with self.lock:
            try:
                self.process.stdin.write((text + "\n").encode("utf-8"))
                self.process.stdin.flush()
            except OSError:
                pass

    def stop(self):
        if self.process and self.process.poll() is None:
            self.command("stop")
            try:
                self.process.wait(60)
            except subprocess.TimeoutExpired:
                self.process.kill()


def write_properties():
    os.makedirs(SERVER_DIR, exist_ok=True)
    properties = {
        "server-ip": HOST,
        "server-port": str(PORT),
        "online-mode": "false",
        "level-name": "world",
        "level-seed": SEED,
        "level-type": "DEFAULT",
        "gamemode": "0",
        "difficulty": "0",
        "spawn-monsters": "false",
        "spawn-animals": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "max-players": "64",
        "view-distance": "8",
        "allow-flight": "true",
        "pvp": "true",
        "network-compression-threshold": "256",
        "snooper-enabled": "false",
        "enable-query": "false",
        "enable-rcon": "false",
        "motd": "Fathom multiplayer profile (local)",
        "max-tick-time": "-1",
        "announce-player-achievements": "false",
        "generate-structures": "true",
    }
    with open(os.path.join(SERVER_DIR, "server.properties"), "w", encoding="utf-8") as out:
        for key, value in properties.items():
            out.write("%s=%s\n" % (key, value))


def prepare(args):
    if not args.accept_eula:
        raise SystemExit("The server needs the Minecraft EULA (https://account.mojang.com/documents/minecraft_eula) "
                         "accepted. Re-run with --accept-eula if you accept it.")
    os.makedirs(RUN, exist_ok=True)
    write_properties()
    with open(os.path.join(SERVER_DIR, "eula.txt"), "w", encoding="utf-8") as out:
        out.write("# Accepted by the operator with --accept-eula for a local benchmark server.\neula=true\n")
    server = Server()
    server.start()
    LOG("[prepare] world generated; building the arena")
    for command in arena_commands():
        server.command(command)
        time.sleep(0.05)
    server.command("save-all")
    time.sleep(5)
    server.stop()
    target = os.path.join(RUN, "saves", SAVE_NAME)
    if os.path.isdir(target):
        shutil.rmtree(target)
    shutil.copytree(os.path.join(SERVER_DIR, "world"), target,
                    ignore=shutil.ignore_patterns("session.lock", "playerdata", "stats"))
    LOG("[prepare] singleplayer copy at %s" % target)


def arena_commands():
    out = [
        "gamerule doDaylightCycle false", "time set 6000", "weather clear 1000000",
        "gamerule doMobSpawning false", "gamerule doFireTick false", "gamerule keepInventory true",
        "gamerule naturalRegeneration true", "gamerule sendCommandFeedback false",
        "gamerule commandBlockOutput false", "gamerule logAdminCommands false",
        "setworldspawn 0 %d -4" % STAND_Y,
    ]
    # Clear above the floor and lay it: 81 x 71, in slabs under the fill limit.
    for y in range(FLOOR_Y + 1, FLOOR_Y + 12, 2):
        out.append("fill -40 %d -10 40 %d 60 air" % (y, y + 1))
    out.append("fill -40 %d -10 40 %d 60 stone" % (FLOOR_Y, FLOOR_Y))
    out.append("fill %d %d %d %d %d %d stone" % (PARK[0] - 20, FLOOR_Y, PARK[2] - 20, PARK[0] + 20, FLOOR_Y,
                                                  PARK[2] + 20))
    return out


# ---------------------------------------------------------------------------
# Scenes
# ---------------------------------------------------------------------------

SIDEBAR = ["Players:", "Kills:", "Final_Kills:", "Beds:", "Wins:", "Coins:", "Streak:", "Level:", "Map:Lighthouse",
           "Red:", "Blue:", "Green:", "Yellow:", "Event:Reset", "fathom.local"]
TEAMS = ["red", "blue", "green", "yellow"]


class Director(object):
    """Turns a scene request into bot behaviour and server commands.

    Bots that are not in a scene are disconnected, not moved away. Vanilla 1.8
    decides who can see a teleported player in the tick it moves, against the
    position it last broadcast, and does not look again while it stays within
    4 blocks: a player teleported back from far away stays invisible to
    everyone near its new spot. Logging in is tracked reliably, and the world
    spawn is in the arena, so a scene's bots log in there and take one short
    teleport to their places.
    """

    def __init__(self, server, bots, selector, names):
        self.server = server
        self.bots = bots
        self.selector = selector
        self.names = names
        self.scene = None
        self.tick = 0
        self.plan = []
        self.to_connect = []
        self.setup_done = True
        self.ready_time = None
        self.notes = ""

    def cmd(self, text):
        self.server.command(text)

    def reset_scoreboard(self):
        for objective in ("side", "hp", "tab"):
            self.cmd("scoreboard objectives remove %s" % objective)
        for team in TEAMS:
            self.cmd("scoreboard teams remove %s" % team)

    def equip(self, bot, armour, sword, enchanted):
        name = bot.name
        slots = ["slot.armor.head", "slot.armor.chest", "slot.armor.legs", "slot.armor.feet"]
        items = ["iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"]
        for slot, item in zip(slots, items):
            if armour:
                tag = " 1 0 {ench:[{id:0,lvl:2}]}" if enchanted else ""
                self.cmd("replaceitem entity %s %s %s%s" % (name, slot, item, tag))
            else:
                self.cmd("replaceitem entity %s %s air" % (name, slot))
        if sword:
            tag = " 1 0 {ench:[{id:16,lvl:1}]}" if enchanted else ""
            self.cmd("replaceitem entity %s slot.hotbar.0 %s%s" % (name, sword, tag))
        else:
            self.cmd("replaceitem entity %s slot.hotbar.0 air" % name)

    def disconnect(self, bot):
        if bot.sock is None:
            return
        try:
            self.selector.unregister(bot.sock)
        except (KeyError, ValueError):
            pass
        try:
            bot.sock.close()
        except OSError:
            pass
        bot.sock = None
        bot.play = False

    def teams(self, members):
        for team in TEAMS:
            self.cmd("scoreboard teams add %s" % team)
            self.cmd("scoreboard teams option %s color %s" % (team, team))
        for index, bot in enumerate(members):
            self.cmd("scoreboard teams join %s %s" % (TEAMS[index % len(TEAMS)], bot.name))
        self.cmd("scoreboard objectives add hp health")
        self.cmd("scoreboard objectives setdisplay belowName hp")

    def sidebar(self):
        self.cmd("scoreboard objectives add side dummy FATHOM")
        self.cmd("scoreboard objectives setdisplay sidebar side")
        for index, line in enumerate(SIDEBAR):
            self.cmd("scoreboard players set %s side %d" % (line, 15 - index))

    def layout(self, scene):
        """[(bot, x, z, behaviour, options)] for a scene."""
        bots = self.bots
        plan = []
        if scene == "mp-few":
            for index, bot in enumerate(bots[:5]):
                plan.append((bot, -8 + index * 4, 10 + index * 3.5, "walk", dict(radius=2.5, speed=0.12)))
        elif scene in ("lobby", "hud-heavy"):
            for index, bot in enumerate(bots[:40]):
                x = -18 + (index % 8) * 5 + random.uniform(-1, 1)
                z = 6 + (index // 8) * 6 + random.uniform(-1, 1)
                plan.append((bot, x, z, "walk", dict(radius=random.uniform(1.5, 3.5),
                                                     speed=random.uniform(0.1, 0.25),
                                                     chat=(0.0025 if scene == "lobby" else 0.0125),
                                                     action=0.01)))
        elif scene in ("combat", "combat-particles"):
            fighters = bots[:20]
            for pair in range(10):
                a, b = fighters[pair * 2], fighters[pair * 2 + 1]
                x = -12 + (pair % 5) * 6
                z = 6 + (pair // 5) * 9 + (pair % 2) * 3
                plan.append((a, x - 1.3, z, "fight", dict(speed=0.08, partner=b.name, centre=(x, z))))
                plan.append((b, x + 1.3, z, "fight", dict(speed=0.08, partner=a.name, centre=(x, z))))
        elif scene == "nametags":
            for index, bot in enumerate(bots[:40]):
                plan.append((bot, -14 + (index % 8) * 4, 6 + (index // 8) * 5.5, "stand", {}))
        elif scene == "chunks":
            for index, bot in enumerate(bots[:10]):
                plan.append((bot, -10 + (index % 5) * 5, 14 + (index // 5) * 10, "walk",
                             dict(radius=2.0, speed=0.15)))
        return plan

    def apply(self, scene):
        self.scene = scene
        self.plan = self.layout(scene)
        wanted = set(entry[0].name for entry in self.plan)
        for bot in self.bots:
            if bot.name not in wanted:
                self.disconnect(bot)
            else:
                bot.behaviour = "idle"
                bot.chat_rate = 0.0
                bot.action_rate = 0.0
        self.to_connect = [entry[0] for entry in self.plan if entry[0].sock is None]
        self.setup_done = False
        self.ready_time = None
        self.notes = "%d players in view" % len(self.plan)
        if scene in ("combat", "combat-particles"):
            self.notes += ", iron armour + diamond sword, half enchanted"
        return self.notes

    def setup(self):
        """Once every bot of the scene is in the world: place, equip, decorate."""
        scene = self.scene
        self.reset_scoreboard()
        self.cmd("tp %s %.1f %d %.1f %.1f %.1f" % (BENCH_PLAYER, CAMERA[0], STAND_Y, CAMERA[2], CAMERA_YAW,
                                                   CAMERA_PITCH))
        members = []
        for bot, x, z, behaviour, options in self.plan:
            bot.anchor = (x, STAND_Y, z)
            if "centre" in options:
                bot.anchor = (options["centre"][0], STAND_Y, options["centre"][1])
            bot.radius = options.get("radius", 0.0)
            bot.speed = options.get("speed", 0.0)
            bot.partner = options.get("partner")
            bot.chat_rate = options.get("chat", 0.0)
            bot.action_rate = options.get("action", 0.0)
            bot.behaviour = behaviour
            bot.x, bot.y, bot.z = x, STAND_Y, z
            self.cmd("tp %s %.2f %d %.2f 180 0" % (bot.name, x, STAND_Y, z))
            self.cmd("effect %s 11 1000000 4 true" % bot.name)
            self.cmd("effect %s 10 1000000 2 true" % bot.name)
            members.append(bot)
        armoured = scene in ("combat", "combat-particles")
        for bot in members:
            if armoured:
                self.equip(bot, True, "diamond_sword", bot.index % 2 == 0)
            elif scene in ("lobby", "hud-heavy"):
                self.equip(bot, False, "compass", False)
            else:
                self.equip(bot, False, None, False)
        if scene in ("lobby", "nametags", "hud-heavy"):
            self.teams(members)
        if scene in ("lobby", "hud-heavy"):
            self.sidebar()
        if scene == "hud-heavy":
            self.cmd("scoreboard objectives add tab dummy")
            self.cmd("scoreboard objectives setdisplay list tab")
            for bot in members:
                self.cmd("scoreboard players set %s tab %d" % (bot.name, random.randint(0, 40)))
        if scene == "chunks":
            self.cmd("fill -20 %d 20 20 %d 40 air" % (STAND_Y, STAND_Y + 6))

    def step(self):
        """Connections and setup, a few per tick."""
        for _ in range(4):
            if not self.to_connect:
                break
            bot = self.to_connect.pop(0)
            try:
                bot.connect()
                self.selector.register(bot.sock, selectors.EVENT_READ, bot)
            except OSError as failure:
                LOG("[serve] %s could not connect: %s" % (bot.name, failure))
        if not self.setup_done and not self.to_connect:
            if all(entry[0].play and entry[0].entity_id is not None for entry in self.plan):
                self.setup()
                self.setup_done = True
                self.ready_time = time.perf_counter()

    def on_tick(self):
        """Server commands the scene issues every tick."""
        self.tick += 1
        self.step()
        if not self.setup_done:
            return
        scene = self.scene
        if scene == "combat-particles":
            for _ in range(3):
                pair = random.randint(0, 9)
                x = -12 + (pair % 5) * 6
                z = 6 + (pair // 5) * 9 + (pair % 2) * 3
                self.cmd("particle crit %.1f %d %.1f 0.4 0.8 0.4 0.3 25 force" % (x, STAND_Y + 1, z))
            pair = random.randint(0, 9)
            x = -12 + (pair % 5) * 6
            z = 6 + (pair // 5) * 9 + (pair % 2) * 3
            self.cmd("particle magicCrit %.1f %d %.1f 0.4 0.8 0.4 0.3 15 force" % (x, STAND_Y + 1, z))
            self.cmd("particle reddust %.1f %d %.1f 0.3 0.6 0.3 0 20 force" % (x, STAND_Y + 1, z))
        elif scene == "hud-heavy":
            for _ in range(2):
                self.cmd("scoreboard players set %s side %d" % (random.choice(SIDEBAR), random.randint(0, 999)))
        elif scene == "lobby" and self.tick % 20 == 0:
            self.cmd("scoreboard players set %s side %d" % (random.choice(SIDEBAR), random.randint(0, 99)))
        elif scene == "chunks":
            colour = self.tick % 16
            for patch in range(2):
                spot = (self.tick * 2 + patch) % 12
                x = -15 + (spot % 6) * 6
                z = 22 + (spot // 6) * 8
                self.cmd("fill %d %d %d %d %d %d stained_hardened_clay %d" % (x, STAND_Y, z, x + 2, STAND_Y + 1,
                                                                              z + 2, colour))
            if self.tick % 20 == 0:
                block = "wool" if (self.tick // 20) % 2 == 0 else "air"
                self.cmd("fill 2 %d 30 7 %d 35 %s" % (STAND_Y, STAND_Y + 5, block))


# ---------------------------------------------------------------------------
# Serve
# ---------------------------------------------------------------------------

def read_text(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return None


def write_text(path, text):
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        handle.write(text + "\n")
    # Windows refuses to replace a file another process has open, and the game
    # polls these files every frame. A sharing violation lasts microseconds, so
    # retry briefly rather than let one collision end a twenty-minute run.
    for attempt in range(50):
        try:
            os.replace(temporary, path)
            return
        except PermissionError:
            if attempt == 49:
                raise
            time.sleep(0.02)


def serve(args):
    if not os.path.isfile(os.path.join(SERVER_DIR, "eula.txt")):
        raise SystemExit("Run prepare first.")
    write_properties()
    os.makedirs(CONTROL, exist_ok=True)
    for name in ("request.txt", "ready.txt", "done.txt"):
        path = os.path.join(CONTROL, name)
        if os.path.exists(path):
            os.remove(path)
    server = Server()
    server.start()
    for command in arena_commands()[:10]:
        server.command(command)
    LOG("[serve] server up on %s:%d" % (HOST, PORT))

    # Bots log in at the world spawn, in the arena, not wherever a past run left them.
    playerdata = os.path.join(SERVER_DIR, "world", "playerdata")
    bots = [Bot("Bot%02d" % index, index) for index in range(args.bots)]
    for bot in bots:
        bot.sock = None
        path = os.path.join(playerdata, "%s.dat" % uuid_string(bot.uuid))
        if os.path.exists(path):
            os.remove(path)
    names = dict((bot.uuid, bot.name) for bot in bots)
    selector = selectors.DefaultSelector()
    director = Director(server, bots, selector, names)
    director.apply("mp-empty")
    last_request = None
    next_tick = time.perf_counter()
    tick = 0
    pending_ready = None
    try:
        while True:
            timeout = max(0.0, next_tick - time.perf_counter())
            events = selector.select(timeout) if selector.get_map() else (time.sleep(timeout) or [])
            for key, _ in events:
                bot = key.data
                try:
                    chunk = bot.sock.recv(1 << 16)
                except OSError:
                    chunk = b""
                if not chunk:
                    director.disconnect(bot)
                    LOG("[serve] %s lost its connection" % bot.name)
                    continue
                bot.feed(chunk, names)
            now = time.perf_counter()
            if now < next_tick:
                continue
            next_tick += 0.05
            if now - next_tick > 1.0:
                next_tick = now + 0.05
            tick += 1
            for bot in bots:
                bot.tick(tick)
            director.on_tick()
            if args.verbose and tick % 100 == 0:
                LOG("[serve] tick %d: %d bots connected, know %d entity ids" % (
                    tick, sum(1 for bot in bots if bot.sock is not None), len(Bots.ids)))
            if tick % 2 == 0:
                if os.path.exists(os.path.join(CONTROL, "done.txt")):
                    LOG("[serve] the client is done")
                    break
                # Ad-hoc console commands, one per line, for debugging a scene by hand.
                manual = os.path.join(CONTROL, "cmd.txt")
                if os.path.exists(manual):
                    for line in (read_text(manual) or "").splitlines():
                        if line.strip():
                            server.command(line.strip())
                    os.remove(manual)
                request = read_text(os.path.join(CONTROL, "request.txt"))
                if request and request != last_request:
                    parts = request.split()
                    if len(parts) == 2:
                        last_request = request
                        notes = director.apply(parts[1])
                        LOG("[serve] scene %s (#%s): %s" % (parts[1], parts[0], notes))
                        pending_ready = "%s %s %s" % (parts[0], parts[1], notes)
            if pending_ready and director.setup_done and director.ready_time is not None \
                    and time.perf_counter() >= director.ready_time + args.scene_delay:
                write_text(os.path.join(CONTROL, "ready.txt"), pending_ready)
                LOG("[serve] ready: %s" % pending_ready)
                pending_ready = None
    except KeyboardInterrupt:
        LOG("[serve] interrupted")
    finally:
        for bot in bots:
            director.disconnect(bot)
        server.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command")
    p = sub.add_parser("prepare")
    p.add_argument("--accept-eula", action="store_true")
    s = sub.add_parser("serve")
    s.add_argument("--bots", type=int, default=40)
    s.add_argument("--scene-delay", type=float, default=6.0)
    s.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args)
    elif args.command == "serve":
        serve(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
