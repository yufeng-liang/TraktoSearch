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
    cd = elem.attrib.get('content-desc') or ''
    text = elem.attrib.get('text') or ''
    bounds = elem.attrib.get('bounds')
    if not bounds:
        continue
    parsed = parse_bounds(bounds)
    if not parsed:
        continue
    x1, y1, x2, y2 = parsed
    if y1 > 2700 and ('设置' in cd or '设置' in text or 'settings' in cd.lower() or 'settings' in text.lower()):
        print(elem.tag, bounds, 'text=' + repr(text), 'cd=' + repr(cd),
              'clickable=' + elem.attrib.get('clickable'), 'class=' + elem.attrib.get('class'))
