#!/usr/bin/env python3
"""ADB UI smoke-test helper. Every text action resolves against a fresh UI hierarchy.
Requires an explicitly selected serial; no implicit interaction with another device.
"""
import argparse
import os
import pathlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SDK = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not SDK and (ROOT / 'local.properties').exists():
    SDK = next((line.split('=', 1)[1].strip() for line in
                (ROOT / 'local.properties').read_text().splitlines()
                if line.startswith('sdk.dir=')), None)
ADB = os.environ.get('DAWNYEW_ADB') or (str(pathlib.Path(SDK) / 'platform-tools/adb')
                                      if SDK else shutil.which('adb'))

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--serial', required=True)
    p.add_argument('action', choices=['snapshot', 'tap', 'input', 'back', 'swipe', 'screen', 'assert-text'])
    p.add_argument('values', nargs='*')
    a = p.parse_args()
    if not ADB:
        p.error('Set DAWNYEW_ADB or ANDROID_HOME, or add adb to PATH')
    def adb(*args):
        return subprocess.check_output([str(ADB), '-s', a.serial, *args])
    def tree():
        adb('shell', 'uiautomator', 'dump', '/sdcard/dawnyew-window.xml')
        data = adb('shell', 'cat', '/sdcard/dawnyew-window.xml')
        return ET.fromstring(data)
    def center(node):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
        return str((x1+x2)//2),str((y1+y2)//2)
    if a.action == 'back': adb('shell','input','keyevent','4')
    elif a.action == 'swipe': adb('shell','input','swipe',*a.values)
    elif a.action == 'screen':
        dest=ROOT/'artifacts/device'/a.values[0]
        dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_bytes(adb('exec-out','screencap','-p'))
        print(dest)
    else:
        root=tree()
        if a.action == 'snapshot':
            for node in root.iter('node'):
                text=node.get('text') or node.get('content-desc')
                if text or node.get('class')=='android.widget.EditText' or node.get('checkable')=='true':
                    print(node.get('class'),repr(text),node.get('bounds'), 'enabled='+node.get('enabled',''), 'id='+node.get('resource-id',''), 'checked='+node.get('checked',''))
        elif a.action == 'assert-text':
            texts=[n.get('text','') for n in root.iter('node')]
            if not any(a.values[0] in t for t in texts): raise AssertionError('Expected visible text: '+a.values[0])
            print('PASS visible text: '+a.values[0])
        elif a.action == 'tap':
            matches=[n for n in root.iter('node') if a.values[0] in (n.get('text'),n.get('content-desc'),n.get('resource-id'))]
            if len(a.values)>1:
                parents={c:n for n in root.iter() for c in n}
                def distance(n):
                    d=0
                    while n is not None:
                        if any(a.values[1] in (c.get('text'),c.get('content-desc')) for c in n.iter()): return d
                        n=parents.get(n);d+=1
                    return 10000
                if matches:
                    best=min(distance(n) for n in matches)
                    matches=[n for n in matches if distance(n)==best]
            if len(matches)!=1: raise RuntimeError('Expected one visible match, got '+str(len(matches)))
            adb('shell','input','tap',*center(matches[0]))
        elif a.action == 'input':
            label,value=a.values
            matches=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText' and any(label in (c.get('text'),c.get('content-desc'),c.get('hint'),c.get('resource-id')) for c in n.iter())]
            if len(matches)!=1: raise RuntimeError('Expected one visible editable field, got '+str(len(matches)))
            adb('shell','input','tap',*center(matches[0]))
            # OEM pickers can ignore Ctrl+A; delete the observed value from its end.
            adb('shell','input','keyevent','123')
            existing=matches[0].get('text','')
            adb('shell','input','keyevent',*(['67']*(len(existing)+2)))
            if value: adb('shell','input','text',value.replace(' ','%s'))

if __name__ == '__main__': main()
