import xml.etree.ElementTree as ET
import re
import sys

def parse_bounds(b):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    return tuple(map(int, m.groups())) if m else None

data = open(sys.argv[1], encoding='utf-8').read()
end = data.rfind('</hierarchy>')
root = ET.fromstring(data[:end+12])
for elem in root.iter():
    bounds = elem.attrib.get('bounds')
    clickable = elem.attrib.get('clickable') == 'true'
    text = elem.attrib.get('text') or ''
    cd = elem.attrib.get('content-desc') or ''
    if not bounds:
        continue
    x1,y1,x2,y2 = parse_bounds(bounds)
    if 400 < y1 < 1200 and (clickable or text.strip() or cd.strip()):
        print(bounds, 'text=' + repr(text), 'cd=' + repr(cd), 'clickable=' + str(clickable), 'class=' + elem.attrib.get('class'))
