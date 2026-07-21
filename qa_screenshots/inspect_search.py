import xml.etree.ElementTree as ET
import re
import sys

def parse_bounds(b):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
    return tuple(map(int, m.groups())) if m else None

data = open(sys.argv[1], encoding='utf-8').read()
end = data.rfind('</hierarchy>')
root = ET.fromstring(data[:end+12])
keywords = ['电影', '搜索', '输入', 'search']
for elem in root.iter():
    text = elem.attrib.get('text') or ''
    cd = elem.attrib.get('content-desc') or ''
    bounds = elem.attrib.get('bounds')
    if any(k in text or k in cd for k in keywords):
        print(elem.tag, bounds, 'text=' + repr(text), 'cd=' + repr(cd),
              'clickable=' + elem.attrib.get('clickable'), 'class=' + elem.attrib.get('class'))
