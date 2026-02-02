import hashlib
import json
import struct
import threading

transform_mode = { 'normal': 0, 'onlytranslation': 1, 'norotationorreflection': 2, 'noscale': 3, 'noscaleorreflection': 4 }
blend_mode = {'normal': 0, 'additive': 1, 'multiply': 2, 'screen': 3 }
position_mode = { 'fixed': 0, 'percent': 1 }
spacing_mode = { 'length': 0, 'fixed': 1, 'percent': 2, 'proportional': 3 }
rotate_mode = { 'tangent': 0, 'chain': 1, 'chainscale': 2 }
attachment_type = { 'region': 0, 'boundingbox': 1, 'mesh': 2, 'linkedmesh': 3, 'path': 4, 'point': 5, 'clipping': 6, 'sequence': 7 }

timeline_slot_type = { "attachment": 0, "rgba": 1, "rgb": 2, "rgba2": 3, "rgb2": 4, "alpha": 5 }
timeline_bone_type = { "rotate": 0, "translate": 1, "translatex": 2, "translatey": 3, "scale": 4, "scalex": 5, "scaley": 6, "shear": 7, "shearx": 8, "sheary": 9 }
timeline_attachment_type = { "deform": 0, "sequence": 1 }
timeline_path_type = { "position": 0, "spacing": 1, "mix": 2 }
timeline_curve_type = { "linear": 0, "stepped": 1, "bezier": 2 }

# Global variables for index mappings (used during conversion)
strings_name_to_index = {}
bones_name_to_index = {}
slots_name_to_index = {}
iks_name_to_index = {}
transforms_name_to_index = {}
paths_name_to_index = {}
skins_name_to_index = {}

# Thread lock to prevent concurrent modifications to global variables
_conversion_lock = threading.Lock()



# Main function to convert JSON file to binary
def json_to_skel(json_file, output_file):
    """
    Convert a JSON Spine file to binary .skel format.
    
    IMPORTANT: This function uses global variables and is NOT thread-safe.
    A lock is used to ensure only one conversion runs at a time.
    """
    with _conversion_lock:
        try:
            with open(json_file, "r", encoding="utf-8") as f:
                skeleton_data = json.load(f)
        except UnicodeDecodeError:
            with open(json_file, "r", encoding="utf-8-sig") as f:
                skeleton_data = json.load(f)
        
        if not skeleton_data.get('skeleton').get('spine').startswith("4.1"):
            raise Exception("Cannot convert this file, unsupported Spine version.")
        
        write_skeleton_data_to_binary(skeleton_data, output_file)



# Function to write skeleton data into binary
def write_skeleton_data_to_binary(skeleton_data, output_file):
    global strings_name_to_index
    global bones_name_to_index
    global slots_name_to_index
    global iks_name_to_index
    global transforms_name_to_index
    global paths_name_to_index
    global skins_name_to_index
    
    bones = skeleton_data.get('bones', [])
    bones_name_to_index = {bone['name']: index for index, bone in enumerate(bones)}
    
    slots = skeleton_data.get('slots', [])
    slots_name_to_index = {slot['name']: index for index, slot in enumerate(slots)}
    
    iks = skeleton_data.get('ik', [])
    iks_name_to_index = {ik['name']: index for index, ik in enumerate(iks)}
    
    transforms = skeleton_data.get('transform', [])
    transforms_name_to_index = {transform['name']: index for index, transform in enumerate(transforms)}
    
    paths = skeleton_data.get('path', [])
    paths_name_to_index = {path['name']: index for index, path in enumerate(paths)}
    
    skins = skeleton_data.get('skins', [])
    default_skin = next((s for s in skins if s.get('name') == 'default'), None)
    other_skins = [s for s in skins if s.get('name') != 'default']
    skins_in_order = ([default_skin] if default_skin else []) + other_skins
    skins_name_to_index = {skin.get('name'): index for index, skin in enumerate(skins_in_order) if skin.get('name') is not None}
    
    animations = skeleton_data.get('animations', {})

    with open(output_file, 'wb') as binary_file:
        
        # Write the hash
        # I don't know how it's actually generated so I just rehash the
        # hash string from the JSON file, but it doesn't matter anyway
        hash_string = skeleton_data.get('skeleton').get('hash', '')
        hash_bytes = hashlib.sha256(hash_string.encode('utf-8')).digest()
        hash_int = int.from_bytes(hash_bytes[:8], byteorder='big')
        write_long(binary_file, hash_int)

        # Write version string
        write_string(binary_file, skeleton_data.get('skeleton').get('spine', ""))

        # Write basic properties (x, y, width, height)
        write_float(binary_file, skeleton_data.get('skeleton').get('x', 0.0))
        write_float(binary_file, skeleton_data.get('skeleton').get('y', 0.0))
        write_float(binary_file, skeleton_data.get('skeleton').get('width', 0.0))
        write_float(binary_file, skeleton_data.get('skeleton').get('height', 0.0))

        # We're not writing any on the non-essential data to the file since those aren't needed
        # write_bool(binary_file, skeleton_data.get('nonessential', False))
        write_bool(binary_file, False)
        
        # This strings_name_to_index thing is wrong but it seems to be working at the moment...
        # It should actually be a dict for a lot of different strings in the file (attachments, events, etc)
        # Using just the list of attachments in the skin works for BD2 since they don't have events and such so far
        temp = []

        # From skins: skin names, attachment names and paths
        for skin in skins:
            skin_name = skin.get('name')
            if skin_name:
                temp.append(skin_name)

        for skin in skins:
            for attachment in skin.get('attachments', {}).values():
                for name, slot in attachment.items():
                    if name:
                        temp.append(name)
                    if isinstance(slot, dict):
                        if 'path' in slot and slot['path']:
                            temp.append(slot['path'])
                        # Linked mesh references may use skin/parent names via string refs
                        if 'skin' in slot and slot['skin']:
                            temp.append(slot['skin'])
                        if 'parent' in slot and slot['parent']:
                            temp.append(slot['parent'])

        # From slots: default attachment names
        for slot in slots:
            att = slot.get('attachment')
            if att:
                temp.append(att)

        # From animations: slot attachment timeline names
        animations = skeleton_data.get('animations', {})
        for anim in animations.values():
            slots_timelines = anim.get('slots', {})
            for _slot_name, timelines in slots_timelines.items():
                attach_frames = timelines.get('attachment', [])
                for frame in attach_frames:
                    name = frame.get('name')
                    if name:
                        temp.append(name)

        # Build unique list preserving order
        strings_name_to_index = {name: index for index, name in enumerate(dict.fromkeys(temp))}
            
        write_varint(binary_file, len(strings_name_to_index))
        for name in strings_name_to_index:
            write_string(binary_file, name)


        # Bones
        write_varint(binary_file, len(bones))
        i = 0
        for bone in bones:
            # Bone name
            write_string(binary_file, bone['name'])
        
            # Parent bone index
            if i > 0:
                parent_index = bones_name_to_index.get(bone.get('parent'), 0)
                write_varint(binary_file, parent_index)
                
            # Bone properties
            write_float(binary_file, bone.get('rotation', 0.0))
            write_float(binary_file, bone.get('x', 0.0))
            write_float(binary_file, bone.get('y', 0.0))
            write_float(binary_file, bone.get('scaleX', 1.0))
            write_float(binary_file, bone.get('scaleY', 1.0))
            write_float(binary_file, bone.get('shearX', 0.0))
            write_float(binary_file, bone.get('shearY', 0.0))
            write_float(binary_file, bone.get('length', 0.0))
            write_varint(binary_file, transform_mode.get(bone.get('transform', 'normal').lower(), 0))
            write_bool(binary_file, bone.get('skin', False))

            i+=1
            

        # Slots
        write_varint(binary_file, len(slots))
        for slot in slots:
            # Slot name
            write_string(binary_file, slot['name'])
        
            # Bone index the slot is attached to
            bone_index = bones_name_to_index.get(slot['bone'], -1)
            write_varint(binary_file, bone_index)
        
            # Slot colors
            write_rgba(binary_file, slot.get('color'))
            write_rgba(binary_file, slot.get('dark'))
        
            # Attachment name as a reference (index from the strings dict)
            write_string_ref(binary_file, slot.get('attachment'))
        
            # Blend mode
            write_varint(binary_file, blend_mode.get(slot.get('blend', 'normal').lower(), 0))
            

        # IK constraints
        write_varint(binary_file, len(iks))
        for ik in iks:
            write_string(binary_file, ik.get('name'))
            write_varint(binary_file, ik.get('order', 0))
            write_bool(binary_file, ik.get('skin', False))
        
            ik_bones = ik.get('bones', [])
            write_varint(binary_file, len(ik_bones))
            for ik_bone in ik_bones:
                bone_index = bones_name_to_index.get(ik_bone)
                write_varint(binary_file, bone_index)
               
            target_index = bones_name_to_index.get(ik.get('target'))
            write_varint(binary_file, target_index)
            
            write_float(binary_file, ik.get('mix', 1.0))
            write_float(binary_file, ik.get('softness', 0.0))
            write_sbyte(binary_file, 1 if ik.get('bendPositive', True) else -1)
            write_bool(binary_file, ik.get('compress', False))
            write_bool(binary_file, ik.get('stretch', False))
            write_bool(binary_file, ik.get('uniform', False))
            

        # Transform constraints
        write_varint(binary_file, len(transforms))
        for transform in transforms:
            write_string(binary_file, transform.get('name'))
            write_varint(binary_file, transform.get('order', 0))
            write_bool(binary_file, transform.get('skin', False))
        
            transform_bones = transform.get('bones', [])
            write_varint(binary_file, len(transform_bones))
            for transform_bone in transform_bones:
                bone_index = bones_name_to_index.get(transform_bone)
                write_varint(binary_file, bone_index)
               
            target_index = bones_name_to_index.get(transform.get('target'))
            write_varint(binary_file, target_index)
            
            write_bool(binary_file, transform.get('local', False))
            write_bool(binary_file, transform.get('relative', False))
            
            write_float(binary_file, transform.get('rotation', 0.0))
            write_float(binary_file, transform.get('x', 0.0))
            write_float(binary_file, transform.get('y', 0.0))
            write_float(binary_file, transform.get('scaleX', 0.0))
            write_float(binary_file, transform.get('scaleY', 0.0))
            write_float(binary_file, transform.get('shearY', 0.0))
            
            write_float(binary_file, transform.get('mixRotate', 1.0))
            write_float(binary_file, transform.get('mixX', 1.0))
            write_float(binary_file, transform.get('mixY', transform.get('mixX', 1.0)))
            write_float(binary_file, transform.get('mixScaleX', 1.0))
            write_float(binary_file, transform.get('mixScaleY', transform.get('mixScaleX', 1.0)))
            write_float(binary_file, transform.get('mixShearY', 1.0))


        # Path constraints
        write_varint(binary_file, len(paths))
        for path in paths:
            write_string(binary_file, path.get('name'))
            write_varint(binary_file, path.get('order', 0))
            write_bool(binary_file, path.get('skin', False))
        
            path_bones = path.get('bones', [])
            write_varint(binary_file, len(path_bones))
            for path_bone in path_bones:
                bone_index = bones_name_to_index.get(path_bone)
                write_varint(binary_file, bone_index)
               
            target_index = slots_name_to_index.get(path.get('target'))
            write_varint(binary_file, target_index)
            
            write_varint(binary_file, position_mode.get(path.get('positionMode', 'percent').lower(), 1))
            write_varint(binary_file, spacing_mode.get(path.get('spacingMode', 'length').lower(), 0))
            write_varint(binary_file, rotate_mode.get(path.get('rotateMode', 'tangent').lower(), 0))
            
            write_float(binary_file, path.get('rotation', 0.0))
            write_float(binary_file, path.get('position', 0.0))
            write_float(binary_file, path.get('spacing', 0.0))
            
            write_float(binary_file, path.get('mixRotate', 1.0))
            write_float(binary_file, path.get('mixX', 1.0))
            write_float(binary_file, path.get('mixY', path.get('mixX', 1.0)))
            
        # Skins
        if default_skin == None:
            write_varint(binary_file, 0)
        else:
            skin_attachments = default_skin.get('attachments', {})
            write_varint(binary_file, len(skin_attachments))
            for key, entry in skin_attachments.items():
                write_varint(binary_file, slots_name_to_index.get(key))
                write_varint(binary_file, len(entry))   
                for name, attachment in entry.items():
                    write_string_ref(binary_file, name)
                    write_attachment(binary_file, attachment, name)
                    
        # Other skins
        write_varint(binary_file, len(other_skins))
        for skin in other_skins:
            write_string_ref(binary_file, skin.get('name'))

            skin_bones = skin.get('bones') or []
            write_varint(binary_file, len(skin_bones))
            for bone_name in skin_bones:
                write_varint(binary_file, bones_name_to_index.get(bone_name, 0))

            skin_ik = skin.get('ik') or []
            write_varint(binary_file, len(skin_ik))
            for constraint_name in skin_ik:
                write_varint(binary_file, iks_name_to_index.get(constraint_name, 0))

            skin_transform = skin.get('transform') or []
            write_varint(binary_file, len(skin_transform))
            for constraint_name in skin_transform:
                write_varint(binary_file, transforms_name_to_index.get(constraint_name, 0))

            skin_path = skin.get('path') or []
            write_varint(binary_file, len(skin_path))
            for constraint_name in skin_path:
                write_varint(binary_file, paths_name_to_index.get(constraint_name, 0))

            skin_attachments = skin.get('attachments', {})
            write_varint(binary_file, len(skin_attachments))
            for key, entry in skin_attachments.items():
                write_varint(binary_file, slots_name_to_index.get(key, 0))
                write_varint(binary_file, len(entry))
                for name, attachment in entry.items():
                    write_string_ref(binary_file, name)
                    write_attachment(binary_file, attachment, name)
        
        # Events
        # o = skeletonData.events.Resize(n = input.ReadInt(true)).Items;
        # for (int i = 0; i < n; i++) {
        #     EventData data = new EventData(input.ReadStringRef());
        #     data.Int = input.ReadInt(false);
        #     data.Float = input.ReadFloat();
        #     data.String = input.ReadString();
        #     data.AudioPath = input.ReadString();
        #     if (data.AudioPath != null) {
        #         data.Volume = input.ReadFloat();
        #         data.Balance = input.ReadFloat();
        #     }
        #     o[i] = data;
        # }
        # No events, at least with BD2
        write_varint(binary_file, 0)
        
        # Animations
        # This took a while to reverse
        write_varint(binary_file, len(animations))
        for name, animation in animations.items():
            write_string(binary_file, name)
            write_animation(binary_file, name, animation)

def count_animation_timelines(animation):
    count = 0

    slots = animation.get('slots', {})
    if isinstance(slots, dict):
        for timelines in slots.values():
            if isinstance(timelines, dict):
                count += len(timelines)

    bones = animation.get('bones', {})
    if isinstance(bones, dict):
        for timelines in bones.values():
            if isinstance(timelines, dict):
                count += len(timelines)

    iks = animation.get('ik', {})
    if isinstance(iks, dict):
        count += len(iks)

    transforms = animation.get('transform', {})
    if isinstance(transforms, dict):
        count += len(transforms)

    paths = animation.get('path', {})
    if isinstance(paths, dict):
        for timelines in paths.values():
            if isinstance(timelines, dict):
                count += len(timelines)

    attachments = animation.get('attachments', {})
    if isinstance(attachments, dict):
        for skin in attachments.values():
            if not isinstance(skin, dict):
                continue
            for slot in skin.values():
                if not isinstance(slot, dict):
                    continue
                for attachment in slot.values():
                    if isinstance(attachment, dict):
                        count += len(attachment)

    draw_order = animation.get('drawOrder')
    if draw_order:
        count += 1

    events = animation.get('events')
    if events:
        count += 1

    return count

def write_animation(binary_file, name, animation):
    timeline_count = count_animation_timelines(animation)
    write_varint(binary_file, timeline_count)

    # Slots timelines
    slots = animation.get('slots', {})
    write_varint(binary_file, len(slots))
    for name, slot in slots.items():
        slot_index = slots_name_to_index.get(name)
        write_varint(binary_file, slot_index)
        write_varint(binary_file, len(slot))
        for ttype, frames in slot.items():
            timeline_type = timeline_slot_type.get(ttype.lower(), -1)
            write_varint(binary_file, timeline_type)
            frames_count = len(frames)
            write_varint(binary_file, frames_count)
            
            if frames_count == 0:
                continue
            
            # attachment
            if timeline_type == 0:
                for frame in frames:
                    write_float(binary_file, frame.get('time', 0.0))
                    write_string_ref(binary_file, frame.get('name'))
            # rgba
            elif timeline_type == 1:
                bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                write_varint(binary_file, bezier)
                for index in range(len(frames)):
                    frame = frames[index]
                    write_float(binary_file, frame.get('time', 0.0))
                    write_rgba(binary_file, frame.get('color'))
                    if index == 0:
                        continue
                    previous_frame = frames[index - 1]
                    curve = previous_frame.get('curve', 'linear')
                    if isinstance(curve, str):
                        write_byte(binary_file, timeline_curve_type.get(curve, 0))
                    else:
                        write_byte(binary_file, timeline_curve_type.get('bezier'))
                        for c in curve:
                            write_float(binary_file, c)
            # rgb
            elif timeline_type == 2:
                bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                write_varint(binary_file, bezier)
                for index in range(len(frames)):
                    frame = frames[index]
                    write_float(binary_file, frame.get('time', 0.0))
                    write_rgb(binary_file, frame.get('color'))
                    if index == 0:
                        continue
                    previous_frame = frames[index - 1]
                    curve = previous_frame.get('curve', 'linear')
                    if isinstance(curve, str):
                        write_byte(binary_file, timeline_curve_type.get(curve, 0))
                    else:
                        write_byte(binary_file, timeline_curve_type.get('bezier'))
                        for c in curve:
                            write_float(binary_file, c)
            # rgba2
            elif timeline_type == 3:
                bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                write_varint(binary_file, bezier)
                for index in range(len(frames)):
                    frame = frames[index]
                    write_float(binary_file, frame.get('time', 0.0))
                    write_rgba(binary_file, frame.get('light'))
                    write_rgb(binary_file, frame.get('dark'))
                    if index == 0:
                        continue
                    previous_frame = frames[index - 1]
                    curve = previous_frame.get('curve', 'linear')
                    if isinstance(curve, str):
                        write_byte(binary_file, timeline_curve_type.get(curve, 0))
                    else:
                        write_byte(binary_file, timeline_curve_type.get('bezier'))
                        for c in curve:
                            write_float(binary_file, c)

            # rgb2 (RGB + RGB)
            elif timeline_type == 4:
                bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                write_varint(binary_file, bezier)
                for index in range(len(frames)):
                    frame = frames[index]
                    write_float(binary_file, frame.get('time', 0.0))
                    write_rgb(binary_file, frame.get('light'))
                    write_rgb(binary_file, frame.get('dark'))
                    if index == 0:
                        continue
                    previous_frame = frames[index - 1]
                    curve = previous_frame.get('curve', 'linear')
                    if isinstance(curve, str):
                        write_byte(binary_file, timeline_curve_type.get(curve, 0))
                    else:
                        write_byte(binary_file, timeline_curve_type.get('bezier'))
                        for c in curve:
                            write_float(binary_file, c)

            # alpha (binary stores values as 1 byte 0..255)
            elif timeline_type == 5:
                bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                write_varint(binary_file, bezier)
                for index in range(len(frames)):
                    frame = frames[index]
                    write_float(binary_file, frame.get('time', 0.0))
                    val = frame.get('value', 0.0)
                    try:
                        b = int(max(0.0, min(1.0, float(val))) * 255 + 0.5)
                    except Exception:
                        b = 0
                    write_byte(binary_file, b)
                    if index == 0:
                        continue
                    previous_frame = frames[index - 1]
                    curve = previous_frame.get('curve', 'linear')
                    if isinstance(curve, str):
                        write_byte(binary_file, timeline_curve_type.get(curve, 0))
                    else:
                        write_byte(binary_file, timeline_curve_type.get('bezier'))
                        for c in curve:
                            write_float(binary_file, c)
                            
    # Bones timelines
    bones = animation.get('bones', {})
    write_varint(binary_file, len(bones))
    for name, bone in bones.items():
        bone_index = bones_name_to_index.get(name)
        write_varint(binary_file, bone_index)
        write_varint(binary_file, len(bone))
        for ttype, frames in bone.items():
            timeline_type = timeline_bone_type.get(ttype.lower(), -1)
            write_varint(binary_file, timeline_type)
            frames_count = len(frames)
            write_varint(binary_file, frames_count)
            
            bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
            write_varint(binary_file, bezier)
            if frames_count == 0:
                continue          
            
            for index in range(len(frames)):
                frame = frames[index]
                write_float(binary_file, frame.get('time', 0.0))
                
                # default for scale, scaleX and scaleY is 1.0
                default_value = 1.0 if timeline_type in [4, 5, 6] else 0.0
                
                # translate, scale or shear will use both "x" and "y"
                if timeline_type in [1, 4, 7]:
                    write_float(binary_file, frame.get('x', default_value))
                    write_float(binary_file, frame.get('y', default_value))
                # rest uses "value"
                else:
                    write_float(binary_file, frame.get('value', default_value))
                
                # first frame, no curve
                if index == 0:
                    continue
                
                previous_frame = frames[index - 1]
                curve = previous_frame.get('curve', 'linear')
                if isinstance(curve, str):
                    write_byte(binary_file, timeline_curve_type.get(curve, 0))
                else:
                    write_byte(binary_file, timeline_curve_type.get('bezier'))
                    for c in curve:
                        write_float(binary_file, c)
        
    # IK constraint timelines
    iks = animation.get('ik', {})
    write_varint(binary_file, len(iks))
    for name, ik in iks.items():
        ik_index = iks_name_to_index.get(name)
        write_varint(binary_file, ik_index)
        frames_count = len(ik)
        write_varint(binary_file, frames_count)
        
        bezier = int(sum(len(f.get('curve')) for f in ik if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
        write_varint(binary_file, bezier)
        
        write_float(binary_file, ik[0].get('time', 0.0))
        write_float(binary_file, ik[0].get('mix', 1.0))
        write_float(binary_file, ik[0].get('softness', 0.0))
        for index in range(frames_count):
            frame = ik[index]
            write_sbyte(binary_file, 1 if frame.get('bendPositive', True) else -1)
            write_bool(binary_file, frame.get('compress', False))
            write_bool(binary_file, frame.get('stretch', False))
            
            if index == frames_count - 1:
                break
            
            next_frame = ik[index + 1]
            write_float(binary_file, next_frame.get('time', 0.0))
            write_float(binary_file, next_frame.get('mix', 1.0))
            write_float(binary_file, next_frame.get('softness', 0.0))
            
            curve = frame.get('curve', 'linear')
            if isinstance(curve, str):
                write_byte(binary_file, timeline_curve_type.get(curve, 0))
            else:
                write_byte(binary_file, timeline_curve_type.get('bezier'))
                for c in curve:
                    write_float(binary_file, c)
                    
    # Transform constraint timelines
    transforms = animation.get('transform', {})
    write_varint(binary_file, len(transforms))
    for name, transform in transforms.items():
        transform_index = transforms_name_to_index.get(name)
        write_varint(binary_file, transform_index)
        frames_count = len(transform)
        write_varint(binary_file, frames_count)
        
        bezier = int(sum(len(f.get('curve')) for f in transform if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
        write_varint(binary_file, bezier)
        
        write_float(binary_file, transform[0].get('time', 0.0))
        write_float(binary_file, transform[0].get('mixRotate', 1.0))
        write_float(binary_file, transform[0].get('mixX', 1.0))
        write_float(binary_file, transform[0].get('mixY', transform[0].get('mixX', 1.0)))
        write_float(binary_file, transform[0].get('mixScaleX', 1.0))
        write_float(binary_file, transform[0].get('mixScaleY', transform[0].get('mixScaleX', 1.0)))
        write_float(binary_file, transform[0].get('mixShearY', 1.0))
        
        for index in range(frames_count):
            frame = transform[index]
            
            if index == frames_count - 1:
                break
            
            next_frame = transform[index + 1]
            write_float(binary_file, next_frame.get('time', 0.0))
            write_float(binary_file, next_frame.get('mixRotate', 1.0))
            write_float(binary_file, next_frame.get('mixX', 1.0))
            write_float(binary_file, next_frame.get('mixY', next_frame.get('mixX', 1.0)))
            write_float(binary_file, next_frame.get('mixScaleX', 1.0))
            write_float(binary_file, next_frame.get('mixScaleY', next_frame.get('mixScaleX', 1.0)))
            write_float(binary_file, next_frame.get('mixShearY', 1.0))
            
            curve = frame.get('curve', 'linear')
            if isinstance(curve, str):
                write_byte(binary_file, timeline_curve_type.get(curve, 0))
            else:
                write_byte(binary_file, timeline_curve_type.get('bezier'))
                for c in curve:
                    write_float(binary_file, c)
                    
    # Path constraint timelines
    paths = animation.get('path', {})
    write_varint(binary_file, len(paths))
    for name, path in paths.items():
        path_index = paths_name_to_index.get(name)
        write_varint(binary_file, path_index)
        write_varint(binary_file, len(path))
        for ttype, frames in path.items():
            timeline_type = timeline_path_type.get(ttype.lower(), -1)
            write_varint(binary_file, timeline_type)
            frames_count = len(frames)
            write_varint(binary_file, frames_count)
            
            bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
            write_varint(binary_file, bezier)
            
            for index in range(len(frames)):
                frame = frames[index]
                
                # Position or Spacing
                if timeline_type == 0 or timeline_type == 1:
                    write_float(binary_file, frame.get('time', 0.0))
                    write_float(binary_file, frame.get('value', 0.0))
                    
                # Mix
                elif timeline_type == 2:
                    write_float(binary_file, frame.get('time', 0.0))
                    write_float(binary_file, frame.get('mixRotate', 1.0))
                    write_float(binary_file, frame.get('mixX', 1.0))
                    write_float(binary_file, frame.get('mixY', frame.get('mixX', 1.0)))
                    
                # first frame, no curve
                if index == 0:
                    continue
                
                previous_frame = frames[index - 1]
                curve = previous_frame.get('curve', 'linear')
                if isinstance(curve, str):
                    write_byte(binary_file, timeline_curve_type.get(curve, 0))
                else:
                    write_byte(binary_file, timeline_curve_type.get('bezier'))
                    for c in curve:
                        write_float(binary_file, c)
    
    # Attachment timelines
    attachments = animation.get('attachments', {})
    write_varint(binary_file, len(attachments))
    for skin_name, skin in attachments.items():
        skin_index = skins_name_to_index.get(skin_name, 0)
        write_varint(binary_file, skin_index)
        write_varint(binary_file, len(skin))
        for slot_name, slot in skin.items():
            slot_index = slots_name_to_index.get(slot_name)
            write_varint(binary_file, slot_index)
            write_varint(binary_file, len(slot))
            for name, attachment in slot.items():
                for ttype, frames in attachment.items():
                    write_string_ref(binary_file, name)
                    timeline_type = timeline_attachment_type.get(ttype.lower(), -1)
                    write_varint(binary_file, timeline_type)
                    frames_count = len(frames)
                    write_varint(binary_file, frames_count)
                    
                    # Deform
                    if timeline_type == 0:
                        bezier = int(sum(len(f.get('curve')) for f in frames if f.get('curve') and not isinstance(f.get('curve'), str)) / 4)
                        write_varint(binary_file, bezier)
            
                        write_float(binary_file, frames[0].get('time', 0.0))
                        for index in range(len(frames)):
                            frame = frames[index]
                            vertices = frame.get('vertices', [])
                            end = len(vertices)
                            
                            write_varint(binary_file, end)
                            
                            if end != 0:
                                start = frame.get('offset', 0)
                                write_varint(binary_file, start)
                                for vertex in vertices:
                                    write_float(binary_file, vertex)
                                    
                            if index == frames_count - 1:
                                break
                            
                            write_float(binary_file, frames[index + 1].get('time', 0.0))
                
                            curve = frame.get('curve', 'linear')
                            if isinstance(curve, str):
                                write_byte(binary_file, timeline_curve_type.get(curve, 0))
                            else:
                                write_byte(binary_file, timeline_curve_type.get('bezier'))
                                for c in curve:
                                    write_float(binary_file, c)
                                    
    # Draw order timelines
    draw_order = animation.get('drawOrder', {})
    write_varint(binary_file, len(draw_order))
    for draw in draw_order:
        write_float(binary_file, draw.get('time', 0.0))
        offsets = draw.get('offsets', [])
        write_varint(binary_file, len(offsets))
        
        for offset in offsets:
            slot_index = slots_name_to_index.get(offset.get('slot'))
            write_varint(binary_file, slot_index)
            write_varint(binary_file, int(offset.get('offset')))
            
    # Event timelines
    draw_order = animation.get('events', {})
    write_varint(binary_file, len(draw_order))




def write_attachment(binary_file, attachment, name):
    write_string_ref(binary_file, attachment.get('name'))
    attach_type = attachment_type.get(attachment.get('type', 'region').lower())
    write_byte(binary_file, attach_type)
    
    # Region
    if attach_type == 0:
        write_string_ref(binary_file, attachment.get('path'))
        write_float(binary_file, attachment.get('rotation', 0.0))
        write_float(binary_file, attachment.get('x', 0.0))
        write_float(binary_file, attachment.get('y', 0.0))
        write_float(binary_file, attachment.get('scaleX', 1.0))
        write_float(binary_file, attachment.get('scaleY', 1.0))
        write_float(binary_file, attachment.get('width', 32.0))
        write_float(binary_file, attachment.get('height', 32.0))
        write_rgba(binary_file, attachment.get('color'))
        write_sequence(binary_file, attachment.get('sequence'))

    # Boundingbox
    elif attach_type == 1:
        vertex_count = attachment.get('vertexCount', 0)
        write_varint(binary_file, vertex_count)
        write_vertices(binary_file, attachment, vertex_count)
    
    # Mesh
    elif attach_type == 2:
        write_string_ref(binary_file, attachment.get('path'))
        write_rgba(binary_file, attachment.get('color'))
        
        uvs = attachment.get('uvs', [])
        vertex_count = int(len(uvs) / 2)
        write_varint(binary_file, vertex_count)
        for uv in uvs:
            write_float(binary_file, uv)
        triangles = attachment.get('triangles', [])
        write_short_array(binary_file, triangles)
        write_vertices(binary_file, attachment, vertex_count)
        write_varint(binary_file, attachment.get('hull', 0))
        write_sequence(binary_file, attachment.get('sequence'))
        
    # Linkedmesh
    elif attach_type == 3:
        write_string_ref(binary_file, attachment.get('path'))
        write_rgba(binary_file, attachment.get('color'))
        write_string_ref(binary_file, attachment.get('skin'))
        write_string_ref(binary_file, attachment.get('parent'))
            
        write_bool(binary_file, attachment.get('timelines', True))
        write_sequence(binary_file, attachment.get('sequence'))
    
    # Path
    elif attach_type == 4:
        write_bool(binary_file, attachment.get('closed', False))
        write_bool(binary_file, attachment.get('constantSpeed', True))
        vertex_count = attachment.get('vertexCount', 0)
        write_varint(binary_file, vertex_count)
        write_vertices(binary_file, attachment, vertex_count)
        for length in attachment.get('lengths', []):
            write_float(binary_file, length)

    # Point
    elif attach_type == 5:
        write_float(binary_file, attachment.get('rotation', 0.0))
        write_float(binary_file, attachment.get('x', 0.0))
        write_float(binary_file, attachment.get('y', 0.0))
        
    # Clipping
    elif attach_type == 6:
        write_varint(binary_file, slots_name_to_index.get(attachment.get('end'), 0))
        vertex_count = attachment.get('vertexCount', 0)
        write_varint(binary_file, vertex_count)
        write_vertices(binary_file, attachment, vertex_count)




def write_sequence(binary_file, sequence):
    if sequence == None:
        write_bool(binary_file, False)
        return
    
    write_bool(binary_file, True)
    write_varint(binary_file, sequence.get('count'))
    write_varint(binary_file, sequence.get('start', 1))
    write_varint(binary_file, sequence.get('digits', 0))
    write_varint(binary_file, sequence.get('setup', 0))
    

def write_vertices(binary_file, attachment, vertex_count):
    vertex_length = vertex_count * 2
    vertices = attachment.get('vertices', [])
    if (len(vertices) == vertex_length):
        write_bool(binary_file, False)
        for vertex in vertices:
            write_float(binary_file, vertex)
    else:
        write_bool(binary_file, True)
        i = 0
        l = len(vertices)
        while i < l:
            bone_count = int(vertices[i])
            i += 1
            write_varint(binary_file, bone_count)
            ll = i + bone_count * 4
            while i < ll:
                write_varint(binary_file, int(vertices[i]))
                i += 1
                write_float(binary_file, vertices[i])
                i += 1
                write_float(binary_file, vertices[i])
                i += 1
                write_float(binary_file, vertices[i])
                i += 1


def write_rgba(binary_file, color, default='ffffffff'):
    color = int(color or default, 16)  # Parse the color as a 32-bit hex value
    r = (color >> 24) & 0xFF  # Extract the red byte
    g = (color >> 16) & 0xFF  # Extract the green byte
    b = (color >> 8) & 0xFF   # Extract the blue byte
    a = color & 0xFF          # Extract the alpha byte

    # Write each byte as a separate value
    write_byte(binary_file, r)
    write_byte(binary_file, g)
    write_byte(binary_file, b)
    write_byte(binary_file, a)
    
def write_rgb(binary_file, color, default='ffffff'):
    color = int(color or default, 16)  # Parse the color as a 24-bit hex value
    
    # Extract RGB components
    r = (color >> 16) & 0xFF  # Extract the red byte
    g = (color >> 8) & 0xFF   # Extract the green byte
    b = color & 0xFF          # Extract the blue byte

    # Write each byte as a separate value
    write_byte(binary_file, r)
    write_byte(binary_file, g)
    write_byte(binary_file, b)

    
# Write strings with length prefix
def write_string(binary_file, string):
    if string is None:
        write_varint(binary_file, 0)
    elif string == "":
        write_varint(binary_file, 1)
    else:
        byte_string = string.encode('utf-8')
        write_varint(binary_file, len(byte_string) + 1)
        binary_file.write(byte_string)
        
# Write index to the string dictionary
def write_string_ref(binary_file, string):
    if string is None:
        write_varint(binary_file, 0)
    else:
        index = strings_name_to_index.get(string, -1) + 1
        write_varint(binary_file, index)
    
def write_byte(binary_file, value):
    binary_file.write(struct.pack('B', value))
    
def write_sbyte(binary_file, value):
    if not -128 <= value <= 127:
        raise ValueError("Value out of range for sbyte: must be between -128 and 127")
    binary_file.write(struct.pack('b', value))
    
def write_bool(binary_file, value):
    binary_file.write(struct.pack('>?', value))
    
def write_int(binary_file, value):
    if value > 0x7FFFFFFF:
        value -= 0x100000000
    binary_file.write(struct.pack('>i', value))
        
def write_varint(binary_file, value, optimize_positive=True):
    if not optimize_positive:
        value = (value << 1) ^ (value >> 31)
    value &= 0xFFFFFFFF

    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            binary_file.write(struct.pack('B', byte | 0x80))
        else:
            binary_file.write(struct.pack('B', byte))
            break

def write_short_array(binary_file, short_array):
    # Write the length of the array as a varint
    write_varint(binary_file, len(short_array))

    for value in short_array:
        if not -32768 <= value <= 32767:
            raise ValueError(f"Value out of range for short: {value}")
        binary_file.write(struct.pack('>H', value & 0xFFFF))
        
def write_long(binary_file, value):
    if value >= (1 << 63):
        value -= (1 << 64)
    binary_file.write(struct.pack('>q', value))
    
# Helper function to write floats
# Spine doesn't save floats with the same precision between JSON and Skel
# So out file will be a little bit different than if it was actually exported with Spine      
def write_float(binary_file, value):
    binary_file.write(struct.pack('>f', value))
