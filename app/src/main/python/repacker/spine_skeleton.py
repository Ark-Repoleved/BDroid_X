# -*- coding: utf-8 -*-
"""
Spine skeleton conversion for Brown Dust 2 mods.

Since the November 2026 update the game runs spine-unity 4.2 and only loads
binary skeletons exported by Spine 4.2 (the game's own files are 4.2.43).
Most mods were made for Spine 4.1, so every skeleton is converted to the 4.2
binary format before it is written into a bundle:

    Spine 4.1 / 4.2 JSON  ->  skeleton dict  ->  Spine 4.2 binary
    Spine 4.1 binary      ->  skeleton dict  ->  Spine 4.2 binary
    Spine 4.2 binary      ->  copied unchanged

The intermediate "skeleton dict" uses the Spine JSON layout, so JSON exports
need no extra step.  The binary readers and the writer follow the official
runtimes (spine-libgdx / spine-csharp SkeletonBinary, branches 4.1 and 4.2).

Keep this module compatible with Python 3.8 (Chaquopy).
"""
import base64
import hashlib
import json
import struct

GAME_SPINE_VERSION = '4.2.43'

INHERIT = ['normal', 'onlyTranslation', 'noRotationOrReflection', 'noScale', 'noScaleOrReflection']
BLEND = ['normal', 'additive', 'multiply', 'screen']
POSITION_MODE = ['fixed', 'percent']
SPACING_MODE = ['length', 'fixed', 'percent', 'proportional']
ROTATE_MODE = ['tangent', 'chain', 'chainScale']
ATTACHMENT_TYPES = ['region', 'boundingbox', 'mesh', 'linkedmesh', 'path', 'point', 'clipping']
SEQUENCE_MODES = ['hold', 'once', 'loop', 'pingpong', 'onceReverse', 'loopReverse', 'pingpongReverse']

SLOT_TIMELINES = ['attachment', 'rgba', 'rgb', 'rgba2', 'rgb2', 'alpha']
BONE_TIMELINES = ['rotate', 'translate', 'translatex', 'translatey', 'scale', 'scalex', 'scaley',
                  'shear', 'shearx', 'sheary', 'inherit']
PATH_TIMELINES = ['position', 'spacing', 'mix']
PHYSICS_TIMELINES = {'inertia': 0, 'strength': 1, 'damping': 2, 'mass': 4, 'wind': 5,
                     'gravity': 6, 'mix': 7, 'reset': 8}
PHYSICS_TIMELINE_NAMES = dict((v, k) for k, v in PHYSICS_TIMELINES.items())

# Bone timelines with two values (x, y); the rest use a single "value".
_BONE_TWO_VALUES = ('translate', 'scale', 'shear')
_BONE_DEFAULT_ONE = ('scale', 'scalex', 'scaley')

CURVE_LINEAR = 0
CURVE_STEPPED = 1
CURVE_BEZIER = 2

_PHYSICS_GLOBALS = ('inertiaGlobal', 'strengthGlobal', 'dampingGlobal', 'massGlobal',
                    'windGlobal', 'gravityGlobal', 'mixGlobal')

_F32 = struct.Struct('>f')
_I32 = struct.Struct('>i')
_I16 = struct.Struct('>h')


class SpineFormatError(ValueError):
    """Raised when a skeleton cannot be read or converted."""


def _enum_index(names, value, default=0):
    if value is None:
        return default
    if isinstance(value, int):
        return value if 0 <= value < len(names) else default
    lowered = str(value).strip().lower()
    for i, name in enumerate(names):
        if name.lower() == lowered:
            return i
    return default


# ---------------------------------------------------------------------------
# Binary input / output
# ---------------------------------------------------------------------------

class _Input(object):
    def __init__(self, data):
        self.data = bytes(data)
        self.pos = 0
        self.strings = []

    def _take(self, n):
        end = self.pos + n
        if end > len(self.data):
            raise SpineFormatError('Unexpected end of skeleton data at byte {}.'.format(self.pos))
        chunk = self.data[self.pos:end]
        self.pos = end
        return chunk

    def byte(self):
        if self.pos >= len(self.data):
            raise SpineFormatError('Unexpected end of skeleton data at byte {}.'.format(self.pos))
        value = self.data[self.pos]
        self.pos += 1
        return value

    def sbyte(self):
        value = self.byte()
        return value - 256 if value > 127 else value

    def boolean(self):
        return self.byte() != 0

    def int32(self):
        return _I32.unpack(self._take(4))[0]

    def short(self):
        return _I16.unpack(self._take(2))[0]

    def float32(self):
        return _F32.unpack(self._take(4))[0]

    def raw(self, n):
        return self._take(n)

    def varint(self, optimize_positive=True):
        result = 0
        shift = 0
        for _ in range(5):
            b = self.byte()
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        result &= 0xFFFFFFFF
        if not optimize_positive:
            result = (result >> 1) ^ -(result & 1)
        elif result & 0x80000000:
            result -= 0x100000000
        return result

    def string(self):
        count = self.varint()
        if count == 0:
            return None
        if count == 1:
            return ''
        return self._take(count - 1).decode('utf-8', 'surrogatepass')

    def string_ref(self):
        index = self.varint()
        if index == 0:
            return None
        if index - 1 >= len(self.strings):
            raise SpineFormatError('String reference {} is out of range.'.format(index))
        return self.strings[index - 1]

    def floats(self, n):
        if n <= 0:
            return []
        values = struct.unpack('>{}f'.format(n), self._take(4 * n))
        return list(values)


class _Output(object):
    def __init__(self, strings=None):
        self.buf = bytearray()
        # Shared string table (string -> index); filled while writing.
        self.strings = strings if strings is not None else {}

    def byte(self, value):
        self.buf.append(int(value) & 0xFF)

    def boolean(self, value):
        self.buf.append(1 if value else 0)

    def int32(self, value):
        value = int(value) & 0xFFFFFFFF
        if value & 0x80000000:
            value -= 0x100000000
        self.buf += _I32.pack(value)

    def float32(self, value):
        try:
            self.buf += _F32.pack(float(value))
        except (OverflowError, struct.error, TypeError, ValueError):
            raise SpineFormatError('Invalid number in skeleton: {!r}'.format(value))

    def floats(self, values):
        for v in values:
            self.float32(v)

    def varint(self, value, optimize_positive=True):
        if value is None:
            raise SpineFormatError('Missing index while writing skeleton (unknown bone/slot/constraint name?).')
        value = int(value)
        if not optimize_positive:
            value = (value << 1) ^ (value >> 31)
        value &= 0xFFFFFFFF
        while True:
            b = value & 0x7F
            value >>= 7
            if value:
                self.buf.append(b | 0x80)
            else:
                self.buf.append(b)
                break

    def string(self, value):
        if value is None:
            self.varint(0)
            return
        data = str(value).encode('utf-8', 'surrogatepass')
        self.varint(len(data) + 1)
        self.buf += data

    def string_ref(self, value):
        if value is None:
            self.varint(0)
            return
        index = self.strings.get(value)
        if index is None:
            index = len(self.strings)
            self.strings[value] = index
        self.varint(index + 1)


# ---------------------------------------------------------------------------
# Small helpers shared by readers and writer
# ---------------------------------------------------------------------------

def _rgba_hex(value):
    return '{:08x}'.format(value & 0xFFFFFFFF)


def _rgb_hex(value):
    return '{:06x}'.format(value & 0xFFFFFF)


def _parse_hex(color, default):
    text = (color or default or '').strip().lstrip('#')
    if len(text) == 6:
        text += 'ff'
    try:
        return int(text[:8], 16)
    except ValueError:
        raise SpineFormatError('Invalid color value: {!r}'.format(color))


def _color_bytes(color, default='ffffffff'):
    value = _parse_hex(color, default)
    return [(value >> 24) & 0xFF, (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]


def _rgb_bytes(color, default='ffffff'):
    text = (color or default).strip().lstrip('#')[:6]
    try:
        value = int(text, 16)
    except ValueError:
        raise SpineFormatError('Invalid color value: {!r}'.format(color))
    return [(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]


def _hash_to_bytes(hash_value):
    """JSON stores the skeleton hash as unpadded base64 of the 8 binary bytes."""
    text = (hash_value or '').strip()
    if text:
        try:
            raw = base64.b64decode(text + '=' * (-len(text) % 4))
            if len(raw) == 8:
                return raw
        except (ValueError, TypeError):
            pass
        return hashlib.sha256(text.encode('utf-8')).digest()[:8]
    return b'\0' * 8


def _hash_to_text(raw):
    if raw == b'\0' * 8:
        return None
    return base64.b64encode(raw).decode('ascii').rstrip('=')


def _read_vertices_41(inp, vertex_count):
    if not inp.boolean():
        return inp.floats(vertex_count * 2)
    out = []
    for _ in range(vertex_count):
        bone_count = inp.varint()
        out.append(bone_count)
        for _ in range(bone_count):
            out.append(inp.varint())
            out.extend(inp.floats(3))
    return out


def _read_vertices_42(inp, weighted):
    vertex_count = inp.varint()
    if not weighted:
        return vertex_count, inp.floats(vertex_count * 2)
    out = []
    for _ in range(vertex_count):
        bone_count = inp.varint()
        out.append(bone_count)
        for _ in range(bone_count):
            out.append(inp.varint())
            out.extend(inp.floats(3))
    return vertex_count, out


def _read_sequence_fields(inp):
    return {'count': inp.varint(), 'start': inp.varint(), 'digits': inp.varint(), 'setup': inp.varint()}


def _read_curve(inp, frame, value_count):
    curve_type = inp.byte()
    if curve_type == CURVE_STEPPED:
        frame['curve'] = 'stepped'
    elif curve_type == CURVE_BEZIER:
        frame['curve'] = inp.floats(4 * value_count)


def _read_timeline1(inp, frame_count, key='value'):
    frames = []
    time, value = inp.floats(2)
    for index in range(frame_count):
        frame = {'time': time, key: value}
        frames.append(frame)
        if index == frame_count - 1:
            break
        time, value = inp.floats(2)
        _read_curve(inp, frame, 1)
    return frames


def _read_timeline2(inp, frame_count, key1='x', key2='y'):
    frames = []
    time, v1, v2 = inp.floats(3)
    for index in range(frame_count):
        frame = {'time': time, key1: v1, key2: v2}
        frames.append(frame)
        if index == frame_count - 1:
            break
        time, v1, v2 = inp.floats(3)
        _read_curve(inp, frame, 2)
    return frames


def _read_color_timeline(inp, frame_count, timeline_type):
    inp.varint()  # bezier count
    frames = []

    def read_values():
        if timeline_type == 1:    # rgba
            return {'color': _rgba_hex(_I32.unpack(inp.raw(4))[0])}
        if timeline_type == 2:    # rgb
            r, g, b = inp.byte(), inp.byte(), inp.byte()
            return {'color': '{:02x}{:02x}{:02x}'.format(r, g, b)}
        if timeline_type == 3:    # rgba2
            light = _rgba_hex(_I32.unpack(inp.raw(4))[0])
            r, g, b = inp.byte(), inp.byte(), inp.byte()
            return {'light': light, 'dark': '{:02x}{:02x}{:02x}'.format(r, g, b)}
        if timeline_type == 4:    # rgb2
            r, g, b = inp.byte(), inp.byte(), inp.byte()
            r2, g2, b2 = inp.byte(), inp.byte(), inp.byte()
            return {'light': '{:02x}{:02x}{:02x}'.format(r, g, b),
                    'dark': '{:02x}{:02x}{:02x}'.format(r2, g2, b2)}
        return {'value': inp.byte() / 255.0}   # alpha

    value_counts = {1: 4, 2: 3, 3: 7, 4: 6, 5: 1}
    time = inp.float32()
    values = read_values()
    for index in range(frame_count):
        frame = {'time': time}
        frame.update(values)
        frames.append(frame)
        if index == frame_count - 1:
            break
        time = inp.float32()
        values = read_values()
        _read_curve(inp, frame, value_counts[timeline_type])
    return frames


def _read_slot_timelines(inp, slots):
    result = {}
    for _ in range(inp.varint()):
        slot_name = slots[inp.varint()]['name']
        timelines = {}
        for _ in range(inp.varint()):
            timeline_type = inp.byte()
            frame_count = inp.varint()
            if timeline_type == 0:
                frames = []
                for _ in range(frame_count):
                    time = inp.float32()
                    frames.append({'time': time, 'name': inp.string_ref()})
                timelines['attachment'] = frames
            elif 1 <= timeline_type <= 5:
                timelines[SLOT_TIMELINES[timeline_type]] = _read_color_timeline(inp, frame_count, timeline_type)
            else:
                raise SpineFormatError('Unknown slot timeline type {}.'.format(timeline_type))
        result[slot_name] = timelines
    return result


def _read_transform_timelines(inp, transforms):
    result = {}
    keys = ('mixRotate', 'mixX', 'mixY', 'mixScaleX', 'mixScaleY', 'mixShearY')
    for _ in range(inp.varint()):
        name = transforms[inp.varint()]['name']
        frame_count = inp.varint()
        inp.varint()  # bezier count
        frames = []
        values = inp.floats(7)
        for index in range(frame_count):
            frame = {'time': values[0]}
            frame.update(zip(keys, values[1:]))
            frames.append(frame)
            if index == frame_count - 1:
                break
            values = inp.floats(7)
            _read_curve(inp, frame, 6)
        result[name] = frames
    return result


def _read_path_timelines(inp, paths):
    result = {}
    for _ in range(inp.varint()):
        name = paths[inp.varint()]['name']
        timelines = {}
        for _ in range(inp.varint()):
            timeline_type = inp.byte()
            frame_count = inp.varint()
            inp.varint()  # bezier count
            if timeline_type in (0, 1):
                timelines[PATH_TIMELINES[timeline_type]] = _read_timeline1(inp, frame_count)
            elif timeline_type == 2:
                frames = []
                values = inp.floats(4)
                for index in range(frame_count):
                    frame = {'time': values[0], 'mixRotate': values[1], 'mixX': values[2], 'mixY': values[3]}
                    frames.append(frame)
                    if index == frame_count - 1:
                        break
                    values = inp.floats(4)
                    _read_curve(inp, frame, 3)
                timelines['mix'] = frames
            else:
                raise SpineFormatError('Unknown path timeline type {}.'.format(timeline_type))
        result[name] = timelines
    return result


def _read_attachment_timelines(inp, skins_in_order, slots):
    result = {}
    for _ in range(inp.varint()):
        skin_index = inp.varint()
        if skin_index >= len(skins_in_order):
            raise SpineFormatError('Attachment timeline skin index {} is out of range.'.format(skin_index))
        skin = skins_in_order[skin_index]
        skin_map = result.setdefault(skin['name'], {})
        for _ in range(inp.varint()):
            slot_index = inp.varint()
            slot_name = slots[slot_index]['name']
            slot_map = skin_map.setdefault(slot_name, {})
            for _ in range(inp.varint()):
                attachment_name = inp.string_ref()
                attachment = ((skin.get('attachments') or {}).get(slot_name) or {}).get(attachment_name)
                if attachment is None:
                    raise SpineFormatError('Timeline attachment not found: {}'.format(attachment_name))
                timeline_type = inp.byte()
                frame_count = inp.varint()
                att_map = slot_map.setdefault(attachment_name, {})
                if timeline_type == 0:
                    inp.varint()  # bezier count
                    frames = []
                    time = inp.float32()
                    for index in range(frame_count):
                        frame = {'time': time}
                        end = inp.varint()
                        if end:
                            start = inp.varint()
                            if start:
                                frame['offset'] = start
                            frame['vertices'] = inp.floats(end)
                        frames.append(frame)
                        if index == frame_count - 1:
                            break
                        time = inp.float32()
                        _read_curve(inp, frame, 1)
                    att_map['deform'] = frames
                elif timeline_type == 1:
                    frames = []
                    for _ in range(frame_count):
                        time = inp.float32()
                        mode_and_index = inp.int32()
                        delay = inp.float32()
                        frames.append({'time': time, 'mode': SEQUENCE_MODES[mode_and_index & 0xF],
                                       'index': mode_and_index >> 4, 'delay': delay})
                    att_map['sequence'] = frames
                else:
                    raise SpineFormatError('Unknown attachment timeline type {}.'.format(timeline_type))
    return result


def _read_draw_order(inp, slots):
    frames = []
    for _ in range(inp.varint()):
        time = inp.float32()
        offsets = []
        for _ in range(inp.varint()):
            slot_name = slots[inp.varint()]['name']
            offsets.append({'slot': slot_name, 'offset': inp.varint()})
        frame = {'time': time}
        if offsets:
            frame['offsets'] = offsets
        frames.append(frame)
    return frames


def _read_events_timeline(inp, events_list, string_flag):
    frames = []
    for _ in range(inp.varint()):
        time = inp.float32()
        event = events_list[inp.varint()]
        frame = {'time': time, 'name': event['name'], 'int': inp.varint(False), 'float': inp.float32()}
        if string_flag:
            if inp.boolean():
                frame['string'] = inp.string()
        else:
            value = inp.string()
            if value is not None:
                frame['string'] = value
        if event['data'].get('audio') is not None:
            frame['volume'] = inp.float32()
            frame['balance'] = inp.float32()
        frames.append(frame)
    return frames


# ---------------------------------------------------------------------------
# Spine 4.1 binary reader
# ---------------------------------------------------------------------------

def read_skel_41(data):
    """Parse a Spine 4.1 binary skeleton into the Spine JSON layout."""
    inp = _Input(data)
    hash_raw = inp.raw(8)
    version = inp.string()
    skeleton = {'hash': _hash_to_text(hash_raw), 'spine': version}
    skeleton['x'], skeleton['y'], skeleton['width'], skeleton['height'] = inp.floats(4)
    nonessential = inp.boolean()
    if nonessential:
        skeleton['fps'] = inp.float32()
        images = inp.string()
        audio = inp.string()
        if images:
            skeleton['images'] = images
        if audio:
            skeleton['audio'] = audio

    inp.strings = [inp.string() for _ in range(inp.varint())]

    bones = []
    for i in range(inp.varint()):
        bone = {'name': inp.string()}
        if i > 0:
            bone['parent'] = bones[inp.varint()]['name']
        (bone['rotation'], bone['x'], bone['y'], bone['scaleX'], bone['scaleY'],
         bone['shearX'], bone['shearY'], bone['length']) = inp.floats(8)
        bone['inherit'] = INHERIT[_enum_index(INHERIT, inp.varint())]
        bone['skin'] = inp.boolean()
        if nonessential:
            bone['color'] = _rgba_hex(inp.int32())
        bones.append(bone)

    slots = []
    for _ in range(inp.varint()):
        slot = {'name': inp.string()}
        slot['bone'] = bones[inp.varint()]['name']
        slot['color'] = _rgba_hex(inp.int32())
        dark = inp.int32()
        if dark != -1:
            slot['dark'] = _rgb_hex(dark)
        attachment = inp.string_ref()
        if attachment is not None:
            slot['attachment'] = attachment
        slot['blend'] = BLEND[_enum_index(BLEND, inp.varint())]
        slots.append(slot)

    iks = []
    for _ in range(inp.varint()):
        ik = {'name': inp.string(), 'order': inp.varint(), 'skin': inp.boolean()}
        ik['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        ik['target'] = bones[inp.varint()]['name']
        ik['mix'], ik['softness'] = inp.floats(2)
        ik['bendPositive'] = inp.sbyte() >= 0
        ik['compress'] = inp.boolean()
        ik['stretch'] = inp.boolean()
        ik['uniform'] = inp.boolean()
        iks.append(ik)

    transforms = []
    for _ in range(inp.varint()):
        tc = {'name': inp.string(), 'order': inp.varint(), 'skin': inp.boolean()}
        tc['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        tc['target'] = bones[inp.varint()]['name']
        tc['local'] = inp.boolean()
        tc['relative'] = inp.boolean()
        (tc['rotation'], tc['x'], tc['y'], tc['scaleX'], tc['scaleY'], tc['shearY'],
         tc['mixRotate'], tc['mixX'], tc['mixY'], tc['mixScaleX'], tc['mixScaleY'],
         tc['mixShearY']) = inp.floats(12)
        transforms.append(tc)

    paths = []
    for _ in range(inp.varint()):
        pc = {'name': inp.string(), 'order': inp.varint(), 'skin': inp.boolean()}
        pc['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        pc['target'] = slots[inp.varint()]['name']
        pc['positionMode'] = POSITION_MODE[_enum_index(POSITION_MODE, inp.varint(), 1)]
        pc['spacingMode'] = SPACING_MODE[_enum_index(SPACING_MODE, inp.varint())]
        pc['rotateMode'] = ROTATE_MODE[_enum_index(ROTATE_MODE, inp.varint())]
        (pc['rotation'], pc['position'], pc['spacing'], pc['mixRotate'], pc['mixX'],
         pc['mixY']) = inp.floats(6)
        paths.append(pc)

    def read_attachment(key):
        name = inp.string_ref()
        att = {}
        if name is not None and name != key:
            att['name'] = name
        type_index = inp.byte()
        if type_index >= len(ATTACHMENT_TYPES):
            raise SpineFormatError('Unknown attachment type {}.'.format(type_index))
        att_type = ATTACHMENT_TYPES[type_index]
        if att_type != 'region':
            att['type'] = att_type
        if att_type == 'region':
            path = inp.string_ref()
            if path is not None:
                att['path'] = path
            (att['rotation'], att['x'], att['y'], att['scaleX'], att['scaleY'],
             att['width'], att['height']) = inp.floats(7)
            att['color'] = _rgba_hex(inp.int32())
            if inp.boolean():
                att['sequence'] = _read_sequence_fields(inp)
        elif att_type == 'boundingbox':
            count = inp.varint()
            att['vertexCount'] = count
            att['vertices'] = _read_vertices_41(inp, count)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'mesh':
            path = inp.string_ref()
            if path is not None:
                att['path'] = path
            att['color'] = _rgba_hex(inp.int32())
            count = inp.varint()
            att['uvs'] = inp.floats(count * 2)
            att['triangles'] = [inp.short() for _ in range(inp.varint())]
            att['vertices'] = _read_vertices_41(inp, count)
            att['hull'] = inp.varint()
            if inp.boolean():
                att['sequence'] = _read_sequence_fields(inp)
            if nonessential:
                att['edges'] = [inp.short() for _ in range(inp.varint())]
                att['width'], att['height'] = inp.floats(2)
        elif att_type == 'linkedmesh':
            path = inp.string_ref()
            if path is not None:
                att['path'] = path
            att['color'] = _rgba_hex(inp.int32())
            skin_name = inp.string_ref()
            if skin_name is not None:
                att['skin'] = skin_name
            att['parent'] = inp.string_ref()
            att['timelines'] = inp.boolean()
            if inp.boolean():
                att['sequence'] = _read_sequence_fields(inp)
            if nonessential:
                att['width'], att['height'] = inp.floats(2)
        elif att_type == 'path':
            att['closed'] = inp.boolean()
            att['constantSpeed'] = inp.boolean()
            count = inp.varint()
            att['vertexCount'] = count
            att['vertices'] = _read_vertices_41(inp, count)
            att['lengths'] = inp.floats(count // 3)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'point':
            att['rotation'], att['x'], att['y'] = inp.floats(3)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'clipping':
            att['end'] = slots[inp.varint()]['name']
            count = inp.varint()
            att['vertexCount'] = count
            att['vertices'] = _read_vertices_41(inp, count)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        return att

    def read_skin(is_default):
        if is_default:
            slot_count = inp.varint()
            if slot_count == 0:
                return None
            skin = {'name': 'default'}
        else:
            skin = {'name': inp.string_ref()}
            skin['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['ik'] = [iks[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['transform'] = [transforms[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['path'] = [paths[inp.varint()]['name'] for _ in range(inp.varint())]
            slot_count = inp.varint()
        attachments = {}
        for _ in range(slot_count):
            slot_name = slots[inp.varint()]['name']
            entries = attachments.setdefault(slot_name, {})
            for _ in range(inp.varint()):
                key = inp.string_ref()
                entries[key] = read_attachment(key)
        skin['attachments'] = attachments
        return skin

    skins = []
    default_skin = read_skin(True)
    if default_skin is not None:
        skins.append(default_skin)
    for _ in range(inp.varint()):
        skins.append(read_skin(False))

    events = {}
    events_list = []
    for _ in range(inp.varint()):
        name = inp.string_ref()
        event = {'int': inp.varint(False), 'float': inp.float32(), 'string': inp.string()}
        audio = inp.string()
        if audio is not None:
            event['audio'] = audio
            event['volume'], event['balance'] = inp.floats(2)
        events[name] = event
        events_list.append({'name': name, 'data': event})

    animations = {}
    for _ in range(inp.varint()):
        name = inp.string()
        anim = {}
        inp.varint()  # timeline count
        anim['slots'] = _read_slot_timelines(inp, slots)

        bone_timelines = {}
        for _ in range(inp.varint()):
            bone_name = bones[inp.varint()]['name']
            timelines = {}
            for _ in range(inp.varint()):
                timeline_type = inp.byte()
                frame_count = inp.varint()
                inp.varint()  # bezier count
                if timeline_type >= 10:
                    raise SpineFormatError('Unknown bone timeline type {}.'.format(timeline_type))
                tname = BONE_TIMELINES[timeline_type]
                if tname in _BONE_TWO_VALUES:
                    timelines[tname] = _read_timeline2(inp, frame_count)
                else:
                    timelines[tname] = _read_timeline1(inp, frame_count)
            bone_timelines[bone_name] = timelines
        anim['bones'] = bone_timelines

        ik_timelines = {}
        for _ in range(inp.varint()):
            ik_name = iks[inp.varint()]['name']
            frame_count = inp.varint()
            inp.varint()  # bezier count
            frames = []
            time, mix, softness = inp.floats(3)
            for index in range(frame_count):
                frame = {'time': time, 'mix': mix, 'softness': softness}
                frame['bendPositive'] = inp.sbyte() >= 0
                frame['compress'] = inp.boolean()
                frame['stretch'] = inp.boolean()
                frames.append(frame)
                if index == frame_count - 1:
                    break
                time, mix, softness = inp.floats(3)
                _read_curve(inp, frame, 2)
            ik_timelines[ik_name] = frames
        anim['ik'] = ik_timelines

        anim['transform'] = _read_transform_timelines(inp, transforms)
        anim['path'] = _read_path_timelines(inp, paths)
        anim['attachments'] = _read_attachment_timelines(inp, skins, slots)
        draw_order = _read_draw_order(inp, slots)
        if draw_order:
            anim['drawOrder'] = draw_order
        event_frames = _read_events_timeline(inp, events_list, True)
        if event_frames:
            anim['events'] = event_frames
        animations[name] = anim

    if inp.pos != len(inp.data):
        raise SpineFormatError('Spine 4.1 skeleton has {} unread trailing bytes.'.format(len(inp.data) - inp.pos))

    return {
        'skeleton': skeleton, 'bones': bones, 'slots': slots, 'ik': iks,
        'transform': transforms, 'path': paths, 'physics': [], 'skins': skins,
        'events': events, 'animations': animations,
    }


# ---------------------------------------------------------------------------
# Spine 4.2 binary reader (used to validate converted output)
# ---------------------------------------------------------------------------

def read_skel_42(data):
    """Parse a Spine 4.2 binary skeleton into the Spine JSON layout."""
    inp = _Input(data)
    hash_raw = inp.raw(8)
    version = inp.string()
    skeleton = {'hash': _hash_to_text(hash_raw), 'spine': version}
    skeleton['x'], skeleton['y'], skeleton['width'], skeleton['height'] = inp.floats(4)
    skeleton['referenceScale'] = inp.float32()
    nonessential = inp.boolean()
    if nonessential:
        skeleton['fps'] = inp.float32()
        images = inp.string()
        audio = inp.string()
        if images:
            skeleton['images'] = images
        if audio:
            skeleton['audio'] = audio

    inp.strings = [inp.string() for _ in range(inp.varint())]

    bones = []
    for i in range(inp.varint()):
        bone = {'name': inp.string()}
        if i > 0:
            bone['parent'] = bones[inp.varint()]['name']
        (bone['rotation'], bone['x'], bone['y'], bone['scaleX'], bone['scaleY'],
         bone['shearX'], bone['shearY'], bone['length']) = inp.floats(8)
        bone['inherit'] = INHERIT[_enum_index(INHERIT, inp.byte())]
        bone['skin'] = inp.boolean()
        if nonessential:
            bone['color'] = _rgba_hex(inp.int32())
            icon = inp.string()
            if icon:
                bone['icon'] = icon
            bone['visible'] = inp.boolean()
        bones.append(bone)

    slots = []
    for _ in range(inp.varint()):
        slot = {'name': inp.string()}
        slot['bone'] = bones[inp.varint()]['name']
        slot['color'] = _rgba_hex(inp.int32())
        dark = inp.int32()
        if dark != -1:
            slot['dark'] = _rgb_hex(dark)
        attachment = inp.string_ref()
        if attachment is not None:
            slot['attachment'] = attachment
        slot['blend'] = BLEND[_enum_index(BLEND, inp.varint())]
        if nonessential:
            slot['visible'] = inp.boolean()
        slots.append(slot)

    iks = []
    for _ in range(inp.varint()):
        ik = {'name': inp.string(), 'order': inp.varint()}
        ik['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        ik['target'] = bones[inp.varint()]['name']
        flags = inp.byte()
        ik['skin'] = bool(flags & 1)
        ik['bendPositive'] = bool(flags & 2)
        ik['compress'] = bool(flags & 4)
        ik['stretch'] = bool(flags & 8)
        ik['uniform'] = bool(flags & 16)
        ik['mix'] = (inp.float32() if flags & 64 else 1.0) if flags & 32 else 0.0
        ik['softness'] = inp.float32() if flags & 128 else 0.0
        iks.append(ik)

    transforms = []
    for _ in range(inp.varint()):
        tc = {'name': inp.string(), 'order': inp.varint()}
        tc['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        tc['target'] = bones[inp.varint()]['name']
        flags = inp.byte()
        tc['skin'] = bool(flags & 1)
        tc['local'] = bool(flags & 2)
        tc['relative'] = bool(flags & 4)
        for bit, key in ((8, 'rotation'), (16, 'x'), (32, 'y'), (64, 'scaleX'), (128, 'scaleY')):
            tc[key] = inp.float32() if flags & bit else 0.0
        flags = inp.byte()
        for bit, key in ((1, 'shearY'), (2, 'mixRotate'), (4, 'mixX'), (8, 'mixY'),
                         (16, 'mixScaleX'), (32, 'mixScaleY'), (64, 'mixShearY')):
            tc[key] = inp.float32() if flags & bit else 0.0
        transforms.append(tc)

    paths = []
    for _ in range(inp.varint()):
        pc = {'name': inp.string(), 'order': inp.varint(), 'skin': inp.boolean()}
        pc['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
        pc['target'] = slots[inp.varint()]['name']
        flags = inp.byte()
        pc['positionMode'] = POSITION_MODE[flags & 1]
        pc['spacingMode'] = SPACING_MODE[(flags >> 1) & 3]
        pc['rotateMode'] = ROTATE_MODE[min((flags >> 3) & 3, len(ROTATE_MODE) - 1)]
        pc['rotation'] = inp.float32() if flags & 128 else 0.0
        pc['position'], pc['spacing'], pc['mixRotate'], pc['mixX'], pc['mixY'] = inp.floats(5)
        paths.append(pc)

    physics = []
    for _ in range(inp.varint()):
        pc = {'name': inp.string(), 'order': inp.varint()}
        pc['bone'] = bones[inp.varint()]['name']
        flags = inp.byte()
        pc['skin'] = bool(flags & 1)
        for bit, key in ((2, 'x'), (4, 'y'), (8, 'rotate'), (16, 'scaleX'), (32, 'shearX')):
            pc[key] = inp.float32() if flags & bit else 0.0
        pc['limit'] = inp.float32() if flags & 64 else 5000.0
        pc['fps'] = inp.byte()
        pc['inertia'], pc['strength'], pc['damping'] = inp.floats(3)
        mass_inverse = inp.float32() if flags & 128 else 1.0
        pc['mass'] = (1.0 / mass_inverse) if mass_inverse else 0.0
        pc['wind'], pc['gravity'] = inp.floats(2)
        flags = inp.byte()
        for i, key in enumerate(_PHYSICS_GLOBALS):
            pc[key] = bool(flags & (1 << i))
        pc['mix'] = inp.float32() if flags & 128 else 1.0
        physics.append(pc)

    skins_by_index = []  # filled as skins are read; linked meshes reference skins by index
    pending_linked = []

    def read_attachment(key):
        flags = inp.byte()
        att = {}
        name = inp.string_ref() if flags & 8 else None
        if name is not None and name != key:
            att['name'] = name
        att_type = ATTACHMENT_TYPES[flags & 7] if (flags & 7) < len(ATTACHMENT_TYPES) else None
        if att_type is None:
            raise SpineFormatError('Unknown attachment type {}.'.format(flags & 7))
        if att_type != 'region':
            att['type'] = att_type
        if att_type == 'region':
            if flags & 16:
                att['path'] = inp.string_ref()
            att['color'] = _rgba_hex(inp.int32()) if flags & 32 else 'ffffffff'
            if flags & 64:
                att['sequence'] = _read_sequence_fields(inp)
            att['rotation'] = inp.float32() if flags & 128 else 0.0
            (att['x'], att['y'], att['scaleX'], att['scaleY'], att['width'],
             att['height']) = inp.floats(6)
        elif att_type == 'boundingbox':
            att['vertexCount'], att['vertices'] = _read_vertices_42(inp, bool(flags & 16))
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'mesh':
            if flags & 16:
                att['path'] = inp.string_ref()
            att['color'] = _rgba_hex(inp.int32()) if flags & 32 else 'ffffffff'
            if flags & 64:
                att['sequence'] = _read_sequence_fields(inp)
            att['hull'] = inp.varint()
            count, att['vertices'] = _read_vertices_42(inp, bool(flags & 128))
            att['uvs'] = inp.floats(count * 2)
            att['triangles'] = [inp.varint() for _ in range((count * 2 - att['hull'] - 2) * 3)]
            if nonessential:
                att['edges'] = [inp.varint() for _ in range(inp.varint())]
                att['width'], att['height'] = inp.floats(2)
        elif att_type == 'linkedmesh':
            if flags & 16:
                att['path'] = inp.string_ref()
            att['color'] = _rgba_hex(inp.int32()) if flags & 32 else 'ffffffff'
            if flags & 64:
                att['sequence'] = _read_sequence_fields(inp)
            att['timelines'] = bool(flags & 128)
            pending_linked.append((att, inp.varint()))
            att['parent'] = inp.string_ref()
            if nonessential:
                att['width'], att['height'] = inp.floats(2)
        elif att_type == 'path':
            att['closed'] = bool(flags & 16)
            att['constantSpeed'] = bool(flags & 32)
            att['vertexCount'], att['vertices'] = _read_vertices_42(inp, bool(flags & 64))
            att['lengths'] = inp.floats(att['vertexCount'] // 3)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'point':
            att['rotation'], att['x'], att['y'] = inp.floats(3)
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        elif att_type == 'clipping':
            att['end'] = slots[inp.varint()]['name']
            att['vertexCount'], att['vertices'] = _read_vertices_42(inp, bool(flags & 16))
            if nonessential:
                att['color'] = _rgba_hex(inp.int32())
        return att

    def read_skin(is_default):
        if is_default:
            slot_count = inp.varint()
            if slot_count == 0:
                return None
            skin = {'name': 'default'}
        else:
            skin = {'name': inp.string()}
            if nonessential:
                skin['color'] = _rgba_hex(inp.int32())
            skin['bones'] = [bones[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['ik'] = [iks[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['transform'] = [transforms[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['path'] = [paths[inp.varint()]['name'] for _ in range(inp.varint())]
            skin['physics'] = [physics[inp.varint()]['name'] for _ in range(inp.varint())]
            slot_count = inp.varint()
        attachments = {}
        for _ in range(slot_count):
            slot_name = slots[inp.varint()]['name']
            entries = attachments.setdefault(slot_name, {})
            for _ in range(inp.varint()):
                key = inp.string_ref()
                entries[key] = read_attachment(key)
        skin['attachments'] = attachments
        return skin

    default_skin = read_skin(True)
    if default_skin is not None:
        skins_by_index.append(default_skin)
    for _ in range(inp.varint()):
        skins_by_index.append(read_skin(False))

    for att, skin_index in pending_linked:
        if skin_index >= len(skins_by_index):
            raise SpineFormatError('Linked mesh skin index {} is out of range.'.format(skin_index))
        skin_name = skins_by_index[skin_index]['name']
        if skin_name != 'default':
            att['skin'] = skin_name

    events = {}
    events_list = []
    for _ in range(inp.varint()):
        name = inp.string()
        event = {'int': inp.varint(False), 'float': inp.float32(), 'string': inp.string()}
        audio = inp.string()
        if audio is not None:
            event['audio'] = audio
            event['volume'], event['balance'] = inp.floats(2)
        events[name] = event
        events_list.append({'name': name, 'data': event})

    animations = {}
    for _ in range(inp.varint()):
        name = inp.string()
        anim = {}
        inp.varint()  # timeline count
        anim['slots'] = _read_slot_timelines(inp, slots)

        bone_timelines = {}
        for _ in range(inp.varint()):
            bone_name = bones[inp.varint()]['name']
            timelines = {}
            for _ in range(inp.varint()):
                timeline_type = inp.byte()
                frame_count = inp.varint()
                if timeline_type == 10:
                    timelines['inherit'] = [
                        {'time': inp.float32(), 'inherit': INHERIT[_enum_index(INHERIT, inp.byte())]}
                        for _ in range(frame_count)
                    ]
                    continue
                if timeline_type > 10:
                    raise SpineFormatError('Unknown bone timeline type {}.'.format(timeline_type))
                inp.varint()  # bezier count
                tname = BONE_TIMELINES[timeline_type]
                if tname in _BONE_TWO_VALUES:
                    timelines[tname] = _read_timeline2(inp, frame_count)
                else:
                    timelines[tname] = _read_timeline1(inp, frame_count)
            bone_timelines[bone_name] = timelines
        anim['bones'] = bone_timelines

        ik_timelines = {}
        for _ in range(inp.varint()):
            ik_name = iks[inp.varint()]['name']
            frame_count = inp.varint()
            inp.varint()  # bezier count
            frames = []
            flags = inp.byte()
            time = inp.float32()
            mix = (inp.float32() if flags & 2 else 1.0) if flags & 1 else 0.0
            softness = inp.float32() if flags & 4 else 0.0
            for index in range(frame_count):
                frame = {'time': time, 'mix': mix, 'softness': softness,
                         'bendPositive': bool(flags & 8), 'compress': bool(flags & 16),
                         'stretch': bool(flags & 32)}
                frames.append(frame)
                if index == frame_count - 1:
                    break
                flags = inp.byte()
                time = inp.float32()
                mix = (inp.float32() if flags & 2 else 1.0) if flags & 1 else 0.0
                softness = inp.float32() if flags & 4 else 0.0
                if flags & 64:
                    frame['curve'] = 'stepped'
                elif flags & 128:
                    frame['curve'] = inp.floats(8)
            ik_timelines[ik_name] = frames
        anim['ik'] = ik_timelines

        anim['transform'] = _read_transform_timelines(inp, transforms)
        anim['path'] = _read_path_timelines(inp, paths)

        physics_timelines = {}
        for _ in range(inp.varint()):
            index = inp.varint() - 1
            pname = physics[index]['name'] if index >= 0 else ''
            timelines = {}
            for _ in range(inp.varint()):
                timeline_type = inp.byte()
                frame_count = inp.varint()
                tname = PHYSICS_TIMELINE_NAMES.get(timeline_type)
                if tname is None:
                    raise SpineFormatError('Unknown physics timeline type {}.'.format(timeline_type))
                if tname == 'reset':
                    timelines['reset'] = [{'time': inp.float32()} for _ in range(frame_count)]
                    continue
                inp.varint()  # bezier count
                timelines[tname] = _read_timeline1(inp, frame_count)
            physics_timelines[pname] = timelines
        anim['physics'] = physics_timelines

        anim['attachments'] = _read_attachment_timelines(inp, skins_by_index, slots)
        draw_order = _read_draw_order(inp, slots)
        if draw_order:
            anim['drawOrder'] = draw_order
        event_frames = _read_events_timeline(inp, events_list, False)
        if event_frames:
            anim['events'] = event_frames
        animations[name] = anim

    if inp.pos != len(inp.data):
        raise SpineFormatError('Spine 4.2 skeleton has {} unread trailing bytes.'.format(len(inp.data) - inp.pos))

    return {
        'skeleton': skeleton, 'bones': bones, 'slots': slots, 'ik': iks,
        'transform': transforms, 'path': paths, 'physics': physics, 'skins': skins_by_index,
        'events': events, 'animations': animations,
    }


# ---------------------------------------------------------------------------
# Spine 4.2 binary writer
# ---------------------------------------------------------------------------

def _named_index(items, kind):
    index = {}
    for i, item in enumerate(items or []):
        name = item.get('name')
        if name is None:
            raise SpineFormatError('A {} has no name.'.format(kind))
        index[name] = i
    return index


def _lookup(index, name, kind):
    if name not in index:
        raise SpineFormatError('{} not found: {}'.format(kind, name))
    return index[name]


def _curve_floats(curve, value_count):
    """Return 4 * value_count bezier floats, padding short curve arrays."""
    values = [float(v) for v in curve]
    needed = 4 * value_count
    if len(values) < 4:
        raise SpineFormatError('Bezier curve has fewer than 4 values.')
    while len(values) < needed:
        values.extend(values[-4:])
    return values[:needed]


def _bezier_count(frames, value_count):
    count = 0
    for frame in frames[:-1]:
        curve = frame.get('curve')
        if isinstance(curve, list):
            count += value_count
    return count


def _write_curve(out, frame, value_count):
    curve = frame.get('curve')
    if curve is None or curve == 'linear':
        out.byte(CURVE_LINEAR)
    elif isinstance(curve, str):
        out.byte(CURVE_STEPPED if curve == 'stepped' else CURVE_LINEAR)
    else:
        out.byte(CURVE_BEZIER)
        out.floats(_curve_floats(curve, value_count))


def _frames(value):
    return value if isinstance(value, list) and value else None


def _weighted(vertices, vertex_count):
    return len(vertices) != vertex_count * 2


def _write_vertices(out, vertices, vertex_count):
    out.varint(vertex_count)
    if not _weighted(vertices, vertex_count):
        out.floats(vertices)
        return
    i = 0
    total = len(vertices)
    for _ in range(vertex_count):
        if i >= total:
            raise SpineFormatError('Weighted vertex data is shorter than the vertex count.')
        bone_count = int(vertices[i])
        i += 1
        out.varint(bone_count)
        for _ in range(bone_count):
            if i + 4 > total:
                raise SpineFormatError('Weighted vertex data is truncated.')
            out.varint(int(vertices[i]))
            out.float32(vertices[i + 1])
            out.float32(vertices[i + 2])
            out.float32(vertices[i + 3])
            i += 4
    if i != total:
        raise SpineFormatError('Weighted vertex data is longer than the vertex count.')


def _write_sequence(out, sequence):
    out.varint(int(sequence.get('count', 0)))
    out.varint(int(sequence.get('start', 1)))
    out.varint(int(sequence.get('digits', 0)))
    out.varint(int(sequence.get('setup', 0)))


class _Writer42(object):
    def __init__(self, skeleton_data, warnings):
        self.data = skeleton_data
        self.warnings = warnings
        self.bones = skeleton_data.get('bones') or []
        self.slots = skeleton_data.get('slots') or []
        self.iks = skeleton_data.get('ik') or []
        self.transforms = skeleton_data.get('transform') or []
        self.paths = skeleton_data.get('path') or []
        self.physics = skeleton_data.get('physics') or []
        self.bone_index = _named_index(self.bones, 'bone')
        self.slot_index = _named_index(self.slots, 'slot')
        self.ik_index = _named_index(self.iks, 'IK constraint')
        self.transform_index = _named_index(self.transforms, 'transform constraint')
        self.path_index = _named_index(self.paths, 'path constraint')
        self.physics_index = _named_index(self.physics, 'physics constraint')

        skins = skeleton_data.get('skins') or []
        if not isinstance(skins, list):
            raise SpineFormatError('Skins must be a list (Spine 3.x skeletons are not supported).')
        self.default_skin = None
        others = []
        for skin in skins:
            if skin.get('name') == 'default' and self.default_skin is None:
                self.default_skin = skin
            else:
                others.append(skin)
        self.other_skins = others
        has_default = self.default_skin is not None and bool(self._skin_slots(self.default_skin))
        self.skins_in_order = ([self.default_skin] if has_default else []) + others
        self.skin_index = dict((s.get('name'), i) for i, s in enumerate(self.skins_in_order))

        events = skeleton_data.get('events') or {}
        self.events = list(events.items())
        self.event_index = dict((name, i) for i, (name, _) in enumerate(self.events))

    def _skin_slots(self, skin):
        attachments = skin.get('attachments') or {}
        return [(slot, entries) for slot, entries in attachments.items() if isinstance(entries, dict)]

    def _skin_ref(self, name):
        if name is None:
            name = 'default'
        if name not in self.skin_index:
            raise SpineFormatError('Skin not found: {}'.format(name))
        return self.skin_index[name]

    # -- skeleton ---------------------------------------------------------

    def write(self, version):
        body = _Output()
        self._write_body(body)

        header = _Output()
        skeleton = self.data.get('skeleton') or {}
        header.buf += _hash_to_bytes(skeleton.get('hash'))
        header.string(version)
        header.float32(skeleton.get('x', 0))
        header.float32(skeleton.get('y', 0))
        header.float32(skeleton.get('width', 0))
        header.float32(skeleton.get('height', 0))
        header.float32(skeleton.get('referenceScale', 100))
        header.boolean(False)  # nonessential data is not needed by the game
        ordered = sorted(body.strings.items(), key=lambda item: item[1])
        header.varint(len(ordered))
        for text, _ in ordered:
            header.string(text)
        return bytes(header.buf + body.buf)

    def _write_body(self, out):
        out.varint(len(self.bones))
        for i, bone in enumerate(self.bones):
            out.string(bone.get('name'))
            if i > 0:
                parent = bone.get('parent')
                if parent is None:
                    raise SpineFormatError('Bone {} has no parent.'.format(bone.get('name')))
                out.varint(_lookup(self.bone_index, parent, 'Parent bone'))
            out.float32(bone.get('rotation', 0))
            out.float32(bone.get('x', 0))
            out.float32(bone.get('y', 0))
            out.float32(bone.get('scaleX', 1))
            out.float32(bone.get('scaleY', 1))
            out.float32(bone.get('shearX', 0))
            out.float32(bone.get('shearY', 0))
            out.float32(bone.get('length', 0))
            inherit = bone.get('inherit', bone.get('transform'))
            out.byte(_enum_index(INHERIT, inherit))
            out.boolean(bone.get('skin', False))

        out.varint(len(self.slots))
        for slot in self.slots:
            out.string(slot.get('name'))
            out.varint(_lookup(self.bone_index, slot.get('bone'), 'Slot bone'))
            out.int32(_parse_hex(slot.get('color'), 'ffffffff'))
            dark = slot.get('dark')
            out.int32(-1 if dark is None else (_parse_hex(dark, None) >> 8))
            out.string_ref(slot.get('attachment'))
            out.varint(_enum_index(BLEND, slot.get('blend')))

        out.varint(len(self.iks))
        for ik in self.iks:
            out.string(ik.get('name'))
            out.varint(int(ik.get('order', 0)))
            ik_bones = ik.get('bones') or []
            out.varint(len(ik_bones))
            for name in ik_bones:
                out.varint(_lookup(self.bone_index, name, 'IK bone'))
            out.varint(_lookup(self.bone_index, ik.get('target'), 'IK target bone'))
            mix = float(ik.get('mix', 1))
            flags = 32 | 128
            if ik.get('skin', False):
                flags |= 1
            if ik.get('bendPositive', True):
                flags |= 2
            if ik.get('compress', False):
                flags |= 4
            if ik.get('stretch', False):
                flags |= 8
            if ik.get('uniform', False):
                flags |= 16
            if mix != 1:
                flags |= 64
            out.byte(flags)
            if mix != 1:
                out.float32(mix)
            out.float32(ik.get('softness', 0))

        out.varint(len(self.transforms))
        for tc in self.transforms:
            out.string(tc.get('name'))
            out.varint(int(tc.get('order', 0)))
            tc_bones = tc.get('bones') or []
            out.varint(len(tc_bones))
            for name in tc_bones:
                out.varint(_lookup(self.bone_index, name, 'Transform constraint bone'))
            out.varint(_lookup(self.bone_index, tc.get('target'), 'Transform constraint target bone'))
            flags = 8 | 16 | 32 | 64 | 128
            if tc.get('skin', False):
                flags |= 1
            if tc.get('local', False):
                flags |= 2
            if tc.get('relative', False):
                flags |= 4
            out.byte(flags)
            out.float32(tc.get('rotation', 0))
            out.float32(tc.get('x', 0))
            out.float32(tc.get('y', 0))
            out.float32(tc.get('scaleX', 0))
            out.float32(tc.get('scaleY', 0))
            out.byte(0x7F)
            mix_x = tc.get('mixX', 1)
            mix_scale_x = tc.get('mixScaleX', 1)
            out.float32(tc.get('shearY', 0))
            out.float32(tc.get('mixRotate', 1))
            out.float32(mix_x)
            out.float32(tc.get('mixY', mix_x))
            out.float32(mix_scale_x)
            out.float32(tc.get('mixScaleY', mix_scale_x))
            out.float32(tc.get('mixShearY', 1))

        out.varint(len(self.paths))
        for pc in self.paths:
            out.string(pc.get('name'))
            out.varint(int(pc.get('order', 0)))
            out.boolean(pc.get('skin', False))
            pc_bones = pc.get('bones') or []
            out.varint(len(pc_bones))
            for name in pc_bones:
                out.varint(_lookup(self.bone_index, name, 'Path constraint bone'))
            out.varint(_lookup(self.slot_index, pc.get('target'), 'Path target slot'))
            flags = (_enum_index(POSITION_MODE, pc.get('positionMode'), 1)
                     | (_enum_index(SPACING_MODE, pc.get('spacingMode')) << 1)
                     | (_enum_index(ROTATE_MODE, pc.get('rotateMode')) << 3)
                     | 128)
            out.byte(flags)
            out.float32(pc.get('rotation', 0))
            out.float32(pc.get('position', 0))
            out.float32(pc.get('spacing', 0))
            out.float32(pc.get('mixRotate', 1))
            out.float32(pc.get('mixX', 1))
            out.float32(pc.get('mixY', 1))

        out.varint(len(self.physics))
        for pc in self.physics:
            out.string(pc.get('name'))
            out.varint(int(pc.get('order', 0)))
            out.varint(_lookup(self.bone_index, pc.get('bone'), 'Physics bone'))
            flags = 2 | 4 | 8 | 16 | 32 | 64 | 128
            if pc.get('skin', False):
                flags |= 1
            out.byte(flags)
            for key in ('x', 'y', 'rotate', 'scaleX', 'shearX'):
                out.float32(pc.get(key, 0))
            out.float32(pc.get('limit', 5000))
            fps = int(round(float(pc.get('fps', 60))))
            out.byte(max(1, min(255, fps)))
            out.float32(pc.get('inertia', 1))
            out.float32(pc.get('strength', 100))
            out.float32(pc.get('damping', 1))
            mass = float(pc.get('mass', 1))
            out.float32(1.0 / mass if mass else 0.0)
            out.float32(pc.get('wind', 0))
            out.float32(pc.get('gravity', 0))
            flags = 128
            for i, key in enumerate(_PHYSICS_GLOBALS):
                if pc.get(key, False):
                    flags |= 1 << i
            out.byte(flags)
            out.float32(pc.get('mix', 1))

        # Default skin, then the other skins.
        if self.skins_in_order and self.skins_in_order[0] is self.default_skin:
            self._write_skin_attachments(out, self.default_skin)
        else:
            out.varint(0)
        out.varint(len(self.other_skins))
        for skin in self.other_skins:
            out.string(skin.get('name'))
            for key, index, kind in (('bones', self.bone_index, 'Skin bone'),
                                     ('ik', self.ik_index, 'Skin IK constraint'),
                                     ('transform', self.transform_index, 'Skin transform constraint'),
                                     ('path', self.path_index, 'Skin path constraint'),
                                     ('physics', self.physics_index, 'Skin physics constraint')):
                names = skin.get(key) or []
                out.varint(len(names))
                for name in names:
                    out.varint(_lookup(index, name, kind))
            self._write_skin_attachments(out, skin)

        out.varint(len(self.events))
        for name, event in self.events:
            event = event or {}
            out.string(name)
            out.varint(int(event.get('int', 0)), False)
            out.float32(event.get('float', 0))
            out.string(event.get('string', ''))
            audio = event.get('audio')
            out.string(audio)
            if audio is not None:
                out.float32(event.get('volume', 1))
                out.float32(event.get('balance', 0))

        animations = self.data.get('animations') or {}
        out.varint(len(animations))
        for name, animation in animations.items():
            out.string(name)
            try:
                self._write_animation(out, animation or {})
            except SpineFormatError as e:
                raise SpineFormatError('Animation {}: {}'.format(name, e))

    def _write_skin_attachments(self, out, skin):
        slots = self._skin_slots(skin)
        out.varint(len(slots))
        for slot_name, entries in slots:
            out.varint(_lookup(self.slot_index, slot_name, 'Skin slot'))
            out.varint(len(entries))
            for key, attachment in entries.items():
                out.string_ref(key)
                try:
                    self._write_attachment(out, key, attachment or {})
                except SpineFormatError as e:
                    raise SpineFormatError('Attachment {} (skin {}): {}'.format(key, skin.get('name'), e))

    def _write_attachment(self, out, key, att):
        att_type = _enum_index(ATTACHMENT_TYPES, att.get('type', 'region'), -1)
        if att_type < 0:
            raise SpineFormatError('Unknown attachment type {!r}.'.format(att.get('type')))
        name = att.get('name')
        flags = att_type
        if name is not None and name != key:
            flags |= 8
        type_name = ATTACHMENT_TYPES[att_type]

        if type_name == 'region':
            path = att.get('path')
            color = att.get('color')
            sequence = att.get('sequence')
            rotation = float(att.get('rotation', 0))
            if path is not None:
                flags |= 16
            if color is not None:
                flags |= 32
            if sequence is not None:
                flags |= 64
            if rotation != 0:
                flags |= 128
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            if path is not None:
                out.string_ref(path)
            if color is not None:
                out.int32(_parse_hex(color, 'ffffffff'))
            if sequence is not None:
                _write_sequence(out, sequence)
            if rotation != 0:
                out.float32(rotation)
            out.float32(att.get('x', 0))
            out.float32(att.get('y', 0))
            out.float32(att.get('scaleX', 1))
            out.float32(att.get('scaleY', 1))
            out.float32(att.get('width', 32))
            out.float32(att.get('height', 32))

        elif type_name in ('boundingbox', 'clipping'):
            vertex_count = int(att.get('vertexCount', 0))
            vertices = att.get('vertices') or []
            if _weighted(vertices, vertex_count):
                flags |= 16
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            if type_name == 'clipping':
                end = att.get('end')
                out.varint(_lookup(self.slot_index, end, 'Clipping end slot') if end is not None else 0)
            _write_vertices(out, vertices, vertex_count)

        elif type_name == 'mesh':
            path = att.get('path')
            color = att.get('color')
            sequence = att.get('sequence')
            uvs = att.get('uvs') or []
            vertex_count = len(uvs) // 2
            vertices = att.get('vertices') or []
            if path is not None:
                flags |= 16
            if color is not None:
                flags |= 32
            if sequence is not None:
                flags |= 64
            if _weighted(vertices, vertex_count):
                flags |= 128
            triangles = [int(t) for t in (att.get('triangles') or [])]
            if len(triangles) % 3:
                raise SpineFormatError('Mesh triangle list length is not a multiple of 3.')
            hull = int(att.get('hull', 0))
            # 4.2 does not store the triangle count; readers derive it from the
            # vertex and hull counts (triangles = 2 * vertices - hull - 2).
            expected_hull = vertex_count * 2 - 2 - len(triangles) // 3
            if hull != expected_hull:
                if expected_hull < 0 or expected_hull > vertex_count:
                    raise SpineFormatError(
                        'Mesh has {} triangles for {} vertices, which Spine 4.2 cannot store.'.format(
                            len(triangles) // 3, vertex_count))
                self.warnings.append('Mesh {}: hull adjusted from {} to {} to match its triangles.'.format(
                    key, hull, expected_hull))
                hull = expected_hull
            if any(t < 0 or t >= vertex_count for t in triangles):
                raise SpineFormatError('Mesh triangle index out of range.')
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            if path is not None:
                out.string_ref(path)
            if color is not None:
                out.int32(_parse_hex(color, 'ffffffff'))
            if sequence is not None:
                _write_sequence(out, sequence)
            out.varint(hull)
            _write_vertices(out, vertices, vertex_count)
            out.floats(uvs[:vertex_count * 2])
            for t in triangles:
                out.varint(t)

        elif type_name == 'linkedmesh':
            path = att.get('path')
            color = att.get('color')
            sequence = att.get('sequence')
            if path is not None:
                flags |= 16
            if color is not None:
                flags |= 32
            if sequence is not None:
                flags |= 64
            if att.get('timelines', True):
                flags |= 128
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            if path is not None:
                out.string_ref(path)
            if color is not None:
                out.int32(_parse_hex(color, 'ffffffff'))
            if sequence is not None:
                _write_sequence(out, sequence)
            out.varint(self._skin_ref(att.get('skin')))
            out.string_ref(att.get('parent'))

        elif type_name == 'path':
            vertex_count = int(att.get('vertexCount', 0))
            vertices = att.get('vertices') or []
            if att.get('closed', False):
                flags |= 16
            if att.get('constantSpeed', True):
                flags |= 32
            if _weighted(vertices, vertex_count):
                flags |= 64
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            _write_vertices(out, vertices, vertex_count)
            lengths = list(att.get('lengths') or [])
            needed = vertex_count // 3
            lengths = (lengths + [0.0] * needed)[:needed]
            out.floats(lengths)

        elif type_name == 'point':
            out.byte(flags)
            if flags & 8:
                out.string_ref(name)
            out.float32(att.get('rotation', 0))
            out.float32(att.get('x', 0))
            out.float32(att.get('y', 0))

    # -- animations -------------------------------------------------------

    def _write_animation(self, out, animation):
        body = _Output(out.strings)
        count = 0

        # Slot timelines.
        slot_entries = []
        for slot_name, timelines in (animation.get('slots') or {}).items():
            items = [(t, f) for t, f in (timelines or {}).items()
                     if _frames(f) and t.lower() in SLOT_TIMELINES]
            if items:
                slot_entries.append((slot_name, items))
        body.varint(len(slot_entries))
        for slot_name, items in slot_entries:
            body.varint(_lookup(self.slot_index, slot_name, 'Slot'))
            body.varint(len(items))
            for tname, frames in items:
                count += 1
                timeline_type = SLOT_TIMELINES.index(tname.lower())
                body.byte(timeline_type)
                body.varint(len(frames))
                if timeline_type == 0:
                    for frame in frames:
                        body.float32(frame.get('time', 0))
                        body.string_ref(frame.get('name'))
                    continue
                value_count = {1: 4, 2: 3, 3: 7, 4: 6, 5: 1}[timeline_type]
                body.varint(_bezier_count(frames, value_count))
                for index, frame in enumerate(frames):
                    body.float32(frame.get('time', 0))
                    if timeline_type == 1:
                        for b in _color_bytes(frame.get('color')):
                            body.byte(b)
                    elif timeline_type == 2:
                        for b in _rgb_bytes(frame.get('color')):
                            body.byte(b)
                    elif timeline_type == 3:
                        for b in _color_bytes(frame.get('light')) + _rgb_bytes(frame.get('dark')):
                            body.byte(b)
                    elif timeline_type == 4:
                        for b in _rgb_bytes(frame.get('light')) + _rgb_bytes(frame.get('dark')):
                            body.byte(b)
                    else:
                        value = float(frame.get('value', 0))
                        body.byte(int(max(0.0, min(1.0, value)) * 255 + 0.5))
                    if index > 0:
                        _write_curve(body, frames[index - 1], value_count)

        # Bone timelines.
        bone_entries = []
        for bone_name, timelines in (animation.get('bones') or {}).items():
            items = [(t, f) for t, f in (timelines or {}).items()
                     if _frames(f) and t.lower() in BONE_TIMELINES]
            if items:
                bone_entries.append((bone_name, items))
        body.varint(len(bone_entries))
        for bone_name, items in bone_entries:
            body.varint(_lookup(self.bone_index, bone_name, 'Bone'))
            body.varint(len(items))
            for tname, frames in items:
                count += 1
                tname = tname.lower()
                timeline_type = BONE_TIMELINES.index(tname)
                body.byte(timeline_type)
                body.varint(len(frames))
                if tname == 'inherit':
                    for frame in frames:
                        body.float32(frame.get('time', 0))
                        body.byte(_enum_index(INHERIT, frame.get('inherit')))
                    continue
                default = 1.0 if tname in _BONE_DEFAULT_ONE else 0.0
                two = tname in _BONE_TWO_VALUES
                body.varint(_bezier_count(frames, 2 if two else 1))
                for index, frame in enumerate(frames):
                    body.float32(frame.get('time', 0))
                    if two:
                        body.float32(frame.get('x', default))
                        body.float32(frame.get('y', default))
                    else:
                        body.float32(frame.get('value', default))
                    if index > 0:
                        _write_curve(body, frames[index - 1], 2 if two else 1)

        # IK constraint timelines.
        ik_entries = [(n, f) for n, f in (animation.get('ik') or {}).items() if _frames(f)]
        body.varint(len(ik_entries))
        for ik_name, frames in ik_entries:
            count += 1
            body.varint(_lookup(self.ik_index, ik_name, 'IK constraint'))
            body.varint(len(frames))
            body.varint(_bezier_count(frames, 2))
            for index, frame in enumerate(frames):
                mix = float(frame.get('mix', 1))
                softness = float(frame.get('softness', 0))
                flags = 0
                if mix != 0:
                    flags |= 1
                    if mix != 1:
                        flags |= 2
                if softness != 0:
                    flags |= 4
                if frame.get('bendPositive', True):
                    flags |= 8
                if frame.get('compress', False):
                    flags |= 16
                if frame.get('stretch', False):
                    flags |= 32
                bezier = None
                if index > 0:
                    curve = frames[index - 1].get('curve')
                    if curve == 'stepped':
                        flags |= 64
                    elif isinstance(curve, list):
                        flags |= 128
                        bezier = _curve_floats(curve, 2)
                body.byte(flags)
                body.float32(frame.get('time', 0))
                if flags & 2:
                    body.float32(mix)
                if flags & 4:
                    body.float32(softness)
                if bezier is not None:
                    body.floats(bezier)

        # Transform constraint timelines.
        tc_entries = [(n, f) for n, f in (animation.get('transform') or {}).items() if _frames(f)]
        body.varint(len(tc_entries))
        for tc_name, frames in tc_entries:
            count += 1
            body.varint(_lookup(self.transform_index, tc_name, 'Transform constraint'))
            body.varint(len(frames))
            body.varint(_bezier_count(frames, 6))
            for index, frame in enumerate(frames):
                mix_x = frame.get('mixX', 1)
                mix_scale_x = frame.get('mixScaleX', 1)
                body.float32(frame.get('time', 0))
                body.float32(frame.get('mixRotate', 1))
                body.float32(mix_x)
                body.float32(frame.get('mixY', mix_x))
                body.float32(mix_scale_x)
                body.float32(frame.get('mixScaleY', mix_scale_x))
                body.float32(frame.get('mixShearY', 1))
                if index > 0:
                    _write_curve(body, frames[index - 1], 6)

        # Path constraint timelines.
        path_entries = []
        for path_name, timelines in (animation.get('path') or {}).items():
            items = [(t, f) for t, f in (timelines or {}).items()
                     if _frames(f) and t.lower() in PATH_TIMELINES]
            if items:
                path_entries.append((path_name, items))
        body.varint(len(path_entries))
        for path_name, items in path_entries:
            body.varint(_lookup(self.path_index, path_name, 'Path constraint'))
            body.varint(len(items))
            for tname, frames in items:
                count += 1
                timeline_type = PATH_TIMELINES.index(tname.lower())
                body.byte(timeline_type)
                body.varint(len(frames))
                if timeline_type == 2:
                    body.varint(_bezier_count(frames, 3))
                    for index, frame in enumerate(frames):
                        mix_x = frame.get('mixX', 1)
                        body.float32(frame.get('time', 0))
                        body.float32(frame.get('mixRotate', 1))
                        body.float32(mix_x)
                        body.float32(frame.get('mixY', mix_x))
                        if index > 0:
                            _write_curve(body, frames[index - 1], 3)
                else:
                    body.varint(_bezier_count(frames, 1))
                    for index, frame in enumerate(frames):
                        body.float32(frame.get('time', 0))
                        body.float32(frame.get('value', 0))
                        if index > 0:
                            _write_curve(body, frames[index - 1], 1)

        # Physics constraint timelines.
        physics_entries = []
        for physics_name, timelines in (animation.get('physics') or {}).items():
            items = [(t, f) for t, f in (timelines or {}).items()
                     if _frames(f) and t in PHYSICS_TIMELINES]
            if items:
                physics_entries.append((physics_name, items))
        body.varint(len(physics_entries))
        for physics_name, items in physics_entries:
            index_plus_one = 0 if not physics_name else _lookup(
                self.physics_index, physics_name, 'Physics constraint') + 1
            body.varint(index_plus_one)
            body.varint(len(items))
            for tname, frames in items:
                count += 1
                body.byte(PHYSICS_TIMELINES[tname])
                body.varint(len(frames))
                if tname == 'reset':
                    for frame in frames:
                        body.float32(frame.get('time', 0))
                    continue
                body.varint(_bezier_count(frames, 1))
                for index, frame in enumerate(frames):
                    body.float32(frame.get('time', 0))
                    body.float32(frame.get('value', 0))
                    if index > 0:
                        _write_curve(body, frames[index - 1], 1)

        # Attachment (deform / sequence) timelines.
        skin_entries = []
        for skin_name, slots in (animation.get('attachments') or {}).items():
            slot_items = []
            for slot_name, attachments in (slots or {}).items():
                pairs = []
                for att_name, timelines in (attachments or {}).items():
                    for tname, frames in (timelines or {}).items():
                        if _frames(frames) and tname in ('deform', 'sequence'):
                            pairs.append((att_name, tname, frames))
                if pairs:
                    slot_items.append((slot_name, pairs))
            if slot_items:
                skin_entries.append((skin_name, slot_items))
        body.varint(len(skin_entries))
        for skin_name, slot_items in skin_entries:
            body.varint(self._skin_ref(skin_name))
            body.varint(len(slot_items))
            for slot_name, pairs in slot_items:
                body.varint(_lookup(self.slot_index, slot_name, 'Slot'))
                body.varint(len(pairs))
                for att_name, tname, frames in pairs:
                    count += 1
                    body.string_ref(att_name)
                    if tname == 'deform':
                        body.byte(0)
                        body.varint(len(frames))
                        body.varint(_bezier_count(frames, 1))
                        body.float32(frames[0].get('time', 0))
                        for index, frame in enumerate(frames):
                            vertices = frame.get('vertices') or []
                            body.varint(len(vertices))
                            if vertices:
                                body.varint(int(frame.get('offset', 0)))
                                body.floats(vertices)
                            if index == len(frames) - 1:
                                break
                            body.float32(frames[index + 1].get('time', 0))
                            _write_curve(body, frame, 1)
                    else:
                        body.byte(1)
                        body.varint(len(frames))
                        last_delay = 0.0
                        for frame in frames:
                            delay = float(frame.get('delay', last_delay))
                            mode = _enum_index(SEQUENCE_MODES, frame.get('mode', 'hold'))
                            body.float32(frame.get('time', 0))
                            body.int32((int(frame.get('index', 0)) << 4) | mode)
                            body.float32(delay)
                            last_delay = delay

        # Draw order timeline.
        draw_order = animation.get('drawOrder') or animation.get('draworder') or []
        if draw_order:
            count += 1
        body.varint(len(draw_order))
        for frame in draw_order:
            body.float32(frame.get('time', 0))
            offsets = []
            for entry in frame.get('offsets') or []:
                offsets.append((_lookup(self.slot_index, entry.get('slot'), 'Draw order slot'),
                                int(entry.get('offset', 0))))
            offsets.sort(key=lambda item: item[0])
            body.varint(len(offsets))
            for slot_index, offset in offsets:
                body.varint(slot_index)
                body.varint(offset)

        # Event timeline.
        event_frames = animation.get('events') or []
        if event_frames:
            count += 1
        body.varint(len(event_frames))
        for frame in event_frames:
            event_name = frame.get('name')
            event_index = _lookup(self.event_index, event_name, 'Event')
            event_data = self.events[event_index][1] or {}
            body.float32(frame.get('time', 0))
            body.varint(event_index)
            body.varint(int(frame.get('int', event_data.get('int', 0))), False)
            body.float32(frame.get('float', event_data.get('float', 0)))
            body.string(frame.get('string'))
            if event_data.get('audio') is not None:
                body.float32(frame.get('volume', event_data.get('volume', 1)))
                body.float32(frame.get('balance', event_data.get('balance', 0)))

        out.varint(count)
        out.buf += body.buf


def write_skel_42(skeleton_data, version=None, warnings=None):
    """Serialize a skeleton dict (Spine JSON layout) to Spine 4.2 binary bytes."""
    if not isinstance(skeleton_data, dict):
        raise SpineFormatError('Skeleton data must be a JSON object.')
    if version is None:
        source = str((skeleton_data.get('skeleton') or {}).get('spine') or '')
        version = source if source.startswith('4.2.') else GAME_SPINE_VERSION
    writer = _Writer42(skeleton_data, warnings if warnings is not None else [])
    return writer.write(version)


# ---------------------------------------------------------------------------
# Detection and conversion entry points
# ---------------------------------------------------------------------------

def _major_minor(version):
    parts = str(version or '').split('.')
    try:
        return int(parts[0]), int(parts[1])
    except (IndexError, ValueError):
        return None


def binary_skel_version(data):
    """Return the version string stored in a Spine 4.x binary skeleton, or None."""
    try:
        inp = _Input(data[:256])
        inp.raw(8)
        version = inp.string()
    except SpineFormatError:
        return None
    if version and all(c.isdigit() or c == '.' for c in version) and _major_minor(version):
        return version
    return None


def load_skeleton_json(json_file):
    try:
        with open(json_file, 'r', encoding='utf-8-sig') as f:
            skeleton_data = json.load(f)
    except UnicodeDecodeError:
        with open(json_file, 'r', encoding='latin-1') as f:
            skeleton_data = json.load(f)
    if not isinstance(skeleton_data, dict) or not isinstance(skeleton_data.get('skeleton'), dict):
        raise SpineFormatError("'{}' is not a Spine skeleton JSON (missing 'skeleton' header).".format(json_file))
    return skeleton_data


def _unsupported(version, what):
    return SpineFormatError(
        'Unsupported Spine {} version {} (only 4.1 and 4.2 can be converted for the current game).'.format(
            what, version or 'unknown'))


def json_to_skel42_bytes(json_file, warnings=None):
    """Convert a Spine 4.1 or 4.2 JSON skeleton file to Spine 4.2 binary bytes.

    Returns (bytes, source_version).
    """
    skeleton_data = load_skeleton_json(json_file)
    version = str(skeleton_data['skeleton'].get('spine') or '')
    if _major_minor(version) not in ((4, 1), (4, 2)):
        raise _unsupported(version, 'JSON')
    data = write_skel_42(skeleton_data, warnings=warnings)
    read_skel_42(data)  # never hand the game a skeleton that does not parse back
    return data, version


def skel_to_skel42_bytes(data, warnings=None):
    """Return Spine 4.2 binary bytes for a 4.1 or 4.2 binary skeleton.

    Returns (bytes, source_version).  4.2 data is returned unchanged.
    """
    version = binary_skel_version(data)
    major_minor = _major_minor(version)
    if major_minor == (4, 2):
        return bytes(data), version
    if major_minor != (4, 1):
        raise _unsupported(version, 'binary')
    converted = write_skel_42(read_skel_41(data), warnings=warnings)
    read_skel_42(converted)
    return converted, version


def convert_skeleton_file(path, warnings=None):
    """Convert a mod skeleton file (.json / .skel / .skel.bytes) to Spine 4.2 binary.

    Returns (bytes, source_version, converted).
    """
    if path.lower().endswith('.json'):
        data, version = json_to_skel42_bytes(path, warnings=warnings)
        return data, version, True
    with open(path, 'rb') as f:
        raw = f.read()
    if binary_skel_version(raw) is None and raw.lstrip(b'\xef\xbb\xbf \t\r\n')[:1] == b'{':
        # A JSON skeleton saved with a .skel extension.
        data, version = json_to_skel42_bytes(path, warnings=warnings)
        return data, version, True
    data, version = skel_to_skel42_bytes(raw, warnings=warnings)
    return data, version, _major_minor(version) != (4, 2)
