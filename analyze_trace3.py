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

    if wire_type == 0:  # varint
        val, pos = decode_varint(data, pos)
        return field_num, wire_type, val, pos
    elif wire_type == 2:  # length-delimited
        length, pos = decode_varint(data, pos)
        if pos + length > len(data):
            return field_num, wire_type, None, len(data)
        val = data[pos:pos+length]
        pos += length
        return field_num, wire_type, val, pos
    elif wire_type == 5:  # 32-bit
        val = struct.unpack('<I', data[pos:pos+4])[0]
        pos += 4
        return field_num, wire_type, val, pos
    elif wire_type == 1:  # 64-bit
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
        if field_num == 1 and wire_type == 2 and val:  # TracePacket
            packets.append(val)

    print(f"TracePackets: {len(packets)}")

    # Analyze packet types
    packet_types = {}
    for pkt in packets:
        if len(pkt) > 0:
            # First field tells us the packet type
            inner_pos = 0
            first_tag, inner_pos = decode_varint(pkt, inner_pos)
            first_field = first_tag >> 3
            packet_types[first_field] = packet_types.get(first_field, 0) + 1

    # Common Perfetto packet field numbers
    field_names = {
        8: "clock_snapshot",
        10: "trace_config",
        11: "trace_stats",
        12: "trigger",
        35: "process_tree",
        41: "track_descriptor",
        42: "track_event",
        44: "interned_data",
        56: "chrome_events",
        58: "sys_stats",
        60: "trace_packet_defaults",
        61: "previous_packet_defaults",
        86: "ftrace_events",
    }

    print("\n=== Packet Types ===")
    for fnum, count in sorted(packet_types.items(), key=lambda x: -x[1]):
        name = field_names.get(fnum, f"field_{fnum}")
        print(f"  {name} (field {fnum}): {count}")

    # Look for process_tree to find our app
    print("\n=== Looking for process info ===")
    for pkt in packets:
        if len(pkt) > 2:
            inner_pos = 0
            tag, inner_pos = decode_varint(pkt, inner_pos)
            field = tag >> 3
            if field == 35:  # process_tree
                # Parse process_tree
                pt_pos = 0
                while pt_pos < len(pkt):
                    fn, wt, val, pt_pos = parse_field(pkt, pt_pos)
                    if fn is None:
                        break
                    if fn == 1 and wt == 2 and val:  # processes
                        # Parse Process message
                        proc_pos = 0
                        pid = None
                        ppid = None
                        cmdline = []
                        while proc_pos < len(val):
                            pfn, pwt, pval, proc_pos = parse_field(val, proc_pos)
                            if pfn is None:
                                break
                            if pfn == 1 and pwt == 0:  # pid
                                pid = pval
                            elif pfn == 2 and pwt == 0:  # ppid
                                ppid = pval
                            elif pfn == 3 and pwt == 2:  # cmdline
                                try:
                                    cmdline.append(pval.decode('utf-8'))
                                except:
                                    pass
                        if cmdline and any('tracktosearch' in c for c in cmdline):
                            print(f"  PID={pid}, cmdline={cmdline}")

if __name__ == '__main__':
    analyze_trace(r'F:\trae-project\cpu-perfetto-20260701T164938.trace')
