import struct

def decode_varint(data, pos):
    result = 0
    shift = 0
    while pos < len(data):
        b = data[pos]
        result |= (b & 0x7f) << shift
        pos += 1
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, pos

def parse_field(data, pos):
    if pos >= len(data):
        return None, None, None, pos
    tag, pos = decode_varint(data, pos)
    field_num = tag >> 3
    wire_type = tag & 0x07

    if wire_type == 0:
        val, pos = decode_varint(data, pos)
        return field_num, wire_type, val, pos
    elif wire_type == 2:
        length, pos = decode_varint(data, pos)
        if pos + length > len(data):
            return field_num, wire_type, None, len(data)
        val = data[pos:pos+length]
        pos += length
        return field_num, wire_type, val, pos
    elif wire_type == 5:
        val = struct.unpack('<I', data[pos:pos+4])[0]
        pos += 4
        return field_num, wire_type, val, pos
    elif wire_type == 1:
        val = struct.unpack('<Q', data[pos:pos+8])[0]
        pos += 8
        return field_num, wire_type, val, pos
    else:
        return field_num, wire_type, None, len(data)

def analyze_trace(filepath):
    with open(filepath, 'rb') as f:
        data = f.read()

    print(f"File size: {len(data)} bytes\n")

    # Parse top-level TracePacket messages
    pos = 0
    packets = []
    while pos < len(data):
        field_num, wire_type, val, pos = parse_field(data, pos)
        if field_num is None:
            break
        if field_num == 1 and wire_type == 2 and val:
            packets.append(val)

    print(f"TracePackets: {len(packets)}")

    # Analyze sys_stats packets for memory info
    print("\n=== System Stats Summary ===")
    mem_values = []
    for pkt in packets:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 58:  # sys_stats
                # Parse sys_stats
                ss_pos = 0
                while ss_pos < len(pkt):
                    fn, wt, val, ss_pos = parse_field(pkt, ss_pos)
                    if fn is None:
                        break
                    if fn == 1 and wt == 2 and val:  # meminfo
                        # Parse MemInfo
                        mi_pos = 0
                        while mi_pos < len(val):
                            mfn, mwt, mval, mi_pos = parse_field(val, mi_pos)
                            if mfn is None:
                                break
                            if mfn == 1 and mwt == 0:  # MemTotal
                                mem_values.append(('MemTotal', mval))
                            elif mfn == 2 and mwt == 0:  # MemFree
                                mem_values.append(('MemFree', mval))
                            elif mfn == 3 and mwt == 0:  # MemAvailable
                                mem_values.append(('MemAvailable', mval))

    if mem_values:
        # Get unique values
        totals = [v for n, v in mem_values if n == 'MemTotal']
        frees = [v for n, v in mem_values if n == 'MemFree']
        avail = [v for n, v in mem_values if n == 'MemAvailable']
        if totals:
            print(f"  MemTotal: {totals[0]/1024/1024:.0f} MB")
        if frees:
            print(f"  MemFree range: {min(frees)/1024/1024:.0f} - {max(frees)/1024/1024:.0f} MB")
        if avail:
            print(f"  MemAvailable range: {min(avail)/1024/1024:.0f} - {max(avail)/1024/1024:.0f} MB")

    # Look for track_descriptor to find tracks
    print("\n=== Track Descriptors ===")
    tracks = {}
    for pkt in packets:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 41:  # track_descriptor
                td_pos = 0
                track_id = None
                name = None
                while td_pos < len(pkt):
                    fn, wt, val, td_pos = parse_field(pkt, td_pos)
                    if fn is None:
                        break
                    if fn == 1 and wt == 0:  # uuid
                        track_id = val
                    elif fn == 2 and wt == 0:  # parent_uuid
                        pass
                    elif fn == 3 and wt == 2:  # name
                        try:
                            name = val.decode('utf-8')
                        except:
                            pass
                    elif fn == 4 and wt == 2:  # process (ProcessDescriptor)
                        pass
                    elif fn == 5 and wt == 2:  # thread (ThreadDescriptor)
                        pass
                if track_id and name:
                    tracks[track_id] = name

    print(f"  Found {len(tracks)} named tracks")
    for tid, name in sorted(tracks.items())[:20]:
        print(f"    UUID={tid}: {name}")

    # Look for track_event (field 11 in TracePacket)
    print("\n=== Track Events ===")
    event_count = 0
    for pkt in packets:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 11:  # track_event
                event_count += 1

    print(f"  Total track events: {event_count}")

if __name__ == '__main__':
    analyze_trace(r'F:\trae-project\cpu-perfetto-20260701T164938.trace')
