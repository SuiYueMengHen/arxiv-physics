#!/usr/bin/env python3
"""Generate Android vectors from the canonical SVG, preserving paths and gradients."""
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
svg = ET.parse(root / 'design/icon.svg').getroot()
ns = {'s': 'http://www.w3.org/2000/svg'}
gradients = {g.get('id'): g for g in svg.findall('s:defs/s:linearGradient', ns)}
paths = svg.findall('s:g/s:path', ns)
background = svg.find('s:path', ns)
res = root / 'app/src/main/res'
def path_xml(p):
    attributes = [f'android:pathData="{p.get("d")}"']
    gradients_xml = []
    for key, android_key in [('fill', 'fillColor'), ('stroke', 'strokeColor')]:
        value = p.get(key)
        if value is None: continue
        if value == 'none': value = '#00000000'
        if value.startswith('url(#'):
            g = gradients[value[5:-1]]
            coords = ' '.join(f'android:{a}="{g.get(b)}"' for a,b in [('startX','x1'),('startY','y1'),('endX','x2'),('endY','y2')])
            stops = ''.join(f'<item android:offset="{t.get("offset")}" android:color="{t.get("stop-color")}"/>' for t in g)
            gradients_xml.append(f'<aapt:attr name="android:{android_key}"><gradient android:type="linear" {coords}>{stops}</gradient></aapt:attr>')
        else: attributes.append(f'android:{android_key}="{value}"')
    if p.get('stroke-width'): attributes.append(f'android:strokeWidth="{p.get("stroke-width")}"')
    if p.get('stroke-linecap'): attributes.append(f'android:strokeLineCap="{p.get("stroke-linecap")}"')
    return '<path ' + ' '.join(attributes) + '>' + ''.join(gradients_xml) + '</path>'
def vector(contents):
    return '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n' + contents + '\n</vector>\n'
group = '<group android:pivotX="54" android:pivotY="54" android:scaleX="0.84" android:scaleY="0.84">' + ''.join(path_xml(p) for p in paths) + '</group>'
(res/'drawable/ic_launcher_foreground.xml').write_text(vector(group))
(res/'drawable/ic_launcher_background.xml').write_text(vector(path_xml(background)))
(res/'drawable/ic_launcher.xml').write_text(vector(path_xml(background) + group))
mono = []
for p in paths:
    if p.get('id') in ('fold-shadow','particle-light'): continue
    p = ET.fromstring(ET.tostring(p))
    if p.get('id') in ('sheet','fold-face'):
        p.set('fill','none'); p.set('stroke','#FFFFFF'); p.set('stroke-width','1.8')
    elif p.get('stroke'): p.set('stroke','#FFFFFF')
    else: p.set('fill','#FFFFFF')
    mono.append(path_xml(p))
(res/'drawable/ic_launcher_monochrome.xml').write_text(vector('<group android:pivotX="54" android:pivotY="54" android:scaleX="0.84" android:scaleY="0.84">'+''.join(mono)+'</group>'))
for version in (26,33):
    monochrome = '<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>' if version == 33 else ''
    (res/f'mipmap-anydpi-v{version}/ic_launcher.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@drawable/ic_launcher_background"/><foreground android:drawable="@drawable/ic_launcher_foreground"/>'+monochrome+'</adaptive-icon>\n')
print('SVG and Android icon resources synchronized')
