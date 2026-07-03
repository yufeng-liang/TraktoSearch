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

    # Analyze all packet types
    print("\n=== All Packet Types (field numbers) ===")
    all_fields = {}
    for pkt in packets:
        if len(pkt) > 0:
            inner_pos = 0
            while inner_pos < len(pkt):
                fn, wt, val, inner_pos = parse_field(pkt, inner_pos)
                if fn is None:
                    break
                all_fields[fn] = all_fields.get(fn, 0) + 1

    for fnum, count in sorted(all_fields.items(), key=lambda x: -x[1])[:30]:
        print(f"  field {fnum}: {count}")

    # Look for specific fields in sys_stats packets
    print("\n=== sys_stats field breakdown ===")
    sys_stats_fields = {}
    for pkt in packets:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 58:  # sys_stats
                ss_pos = 0
                while ss_pos < len(pkt):
                    fn, wt, val, ss_pos = parse_field(pkt, ss_pos)
                    if fn is None:
                        break
                    sys_stats_fields[fn] = sys_stats_fields.get(fn, 0) + 1

    sys_stats_field_names = {
        1: "meminfo",
        2: "vmstat",
        3: "cpuinfo",
        4: "diskstats",
        5: "wifi_stats",
        6: "compass_stats",
        7: "battery_stats",
        8: "thermal_zone",
        9: "cpufreq",
    }
    for fnum, count in sorted(sys_stats_fields.items(), key=lambda x: -x[1]):
        name = sys_stats_field_names.get(fnum, f"field_{fnum}")
        print(f"  {name} (field {fnum}): {count}")

    # Check clock_snapshot for timestamps
    print("\n=== Clock Snapshot Sample ===")
    for pkt in packets[:5]:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 8:  # clock_snapshot
                cs_pos = 0
                clocks = {}
                while cs_pos < len(pkt):
                    fn, wt, val, cs_pos = parse_field(pkt, cs_pos)
                    if fn is None:
                        break
                    if fn == 1 and wt == 2 and val:  # clocks (repeated Clock)
                        c_pos = 0
                        clock_id = None
                        timestamp = None
                        while c_pos < len(val):
                            cfn, cwt, cval, c_pos = parse_field(val, c_pos)
                            if cfn is None:
                                break
                            if cfn == 1 and cwt == 0:  # clock_id
                                clock_id = cval
                            elif cfn == 2 and cwt == 0:  # timestamp
                                timestamp = cval
                        if clock_id is not None and timestamp is not None:
                            clocks[clock_id] = timestamp
                if clocks:
                    print(f"  Clocks: {clocks}")
                    break

if __name__ == '__main__':
    analyze_trace(r'F:\trae-project\cpu-perfetto-20260701T164938.trace')
