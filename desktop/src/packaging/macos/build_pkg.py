#!/usr/bin/env python3
"""macOS kurulum paketi (.pkg) üretir — Linux üzerinde, Apple araçları olmadan.

Yapı (Apple "flat package" / ürün arşivi):
    Distribution          kurulum sihirbazının ayarları
    Resources/welcome.html, conclusion.html
    beje.pkg/PackageInfo, Bom, Payload   (Beje.app -> /Applications)

Payload: cpio (odc) + gzip. Bom: bomutils'in mkbom aracı. Dış kap: xar (bu betik yazar).

Kullanım:
    build_pkg.py --app Beje.app --out Beje.pkg --version 1.1.0 --arch arm64 --mkbom /yol/mkbom
"""
import argparse
import datetime
import gzip
import hashlib
import os
import shutil
import struct
import subprocess
import tempfile
import zlib
from xml.sax.saxutils import escape

PKG_ID = "org.beje.app.pkg"
BUNDLE_ID = "org.beje.app"

WELCOME = """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,Helvetica,sans-serif;font-size:13px;color:#231918}
h2{color:#B6202E;margin-bottom:4px} p{margin:6px 0} .en{color:#666;margin-top:14px}
</style></head><body>
<h2>Bêje %(version)s</h2>
<p>Bêje konuşmanızı tamamen bu Mac'te, internetsiz yazıya döker (Türkçe, Farsça, Arapça).</p>
<p>Kurulum Bêje'yi <b>Uygulamalar</b> klasörüne yerleştirir. Dil paketleri ve Java birlikte kurulur;
internet gerekmez.</p>
<p>Devam etmek için <b>Sürdür</b>'e tıklayın.</p>
<p class="en">Bêje transcribes your speech entirely on this Mac, offline. It will be installed in the
Applications folder together with its language packs.</p>
</body></html>"""

CONCLUSION = """<!DOCTYPE html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,Helvetica,sans-serif;font-size:13px;color:#231918}
h2{color:#1F6C33} p{margin:6px 0} .en{color:#666;margin-top:14px}
</style></head><body>
<h2>Bêje kuruldu</h2>
<p>Bêje'yi <b>Uygulamalar</b> klasöründen ya da Launchpad'den açabilirsiniz.</p>
<p>İlk açılışta mikrofon izni sorulur; <b>İzin Ver</b>'e tıklayın.</p>
<p class="en">Open Bêje from Applications or Launchpad. Allow microphone access when asked.</p>
</body></html>"""


def sha1_file(path):
    digest = hashlib.sha1()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count_payload(root):
    files, size = 0, 0
    for base, dirs, names in os.walk(root):
        files += len(dirs) + len(names)
        for name in names:
            path = os.path.join(base, name)
            if not os.path.islink(path):
                size += os.path.getsize(path)
    return files + 1, size // 1024 + 1


def write_cpio(root, out):
    """odc biçiminde cpio arşivi yazar (macOS Installer Payload biçimi)."""
    inode = 0

    def header(name, mode, size, mtime, nlink):
        nonlocal inode
        inode += 1
        encoded = name.encode("utf-8") + b"\0"
        fields = "%06o%06o%06o%06o%06o%06o%06o%06o%011o%06o%011o" % (
            0o70707, 0, inode, mode, 0, 80, nlink, 0, mtime, len(encoded), size)
        out.write(fields.encode("ascii") + encoded)

    entries = []
    for base, dirs, files in os.walk(root):
        dirs.sort()
        rel = os.path.relpath(base, root)
        entries.append("." if rel == "." else "./" + rel)
        for name in sorted(files):
            entries.append(("./" + name) if rel == "." else "./" + rel + "/" + name)
        # os.walk sembolik bağlantılı klasörlere girmez; onlar dosya gibi eklenir
        for name in list(dirs):
            if os.path.islink(os.path.join(base, name)):
                dirs.remove(name)
                entries.append(("./" + name) if rel == "." else "./" + rel + "/" + name)
    for name in entries:
        path = os.path.join(root, name)
        st = os.lstat(path)
        mtime = int(st.st_mtime)
        if os.path.islink(path):
            target = os.readlink(path).encode("utf-8")
            header(name, 0o120755, len(target), mtime, 1)
            out.write(target)
        elif os.path.isdir(path):
            header(name, 0o40000 | (st.st_mode & 0o7777), 0, mtime, 2)
        else:
            header(name, 0o100000 | (st.st_mode & 0o7777), st.st_size, mtime, 1)
            with open(path, "rb") as fh:
                shutil.copyfileobj(fh, out, 1 << 20)
    header("TRAILER!!!", 0, 0, 0, 1)


def build_component(app, work, version, mkbom):
    """beje.pkg bileşenini (PackageInfo, Bom, Payload) üretir."""
    root = os.path.join(work, "root")
    os.makedirs(root)
    subprocess.run(["cp", "-a", app, os.path.join(root, os.path.basename(app))], check=True)
    files, kbytes = count_payload(root)

    comp = os.path.join(work, "flat", "beje.pkg")
    os.makedirs(comp)
    subprocess.run([mkbom, "-u", "0", "-g", "80", root, os.path.join(comp, "Bom")], check=True)

    # cpio (odc) arşivi: Apple'ın biçimiyle "./" önekli adlar, sahip root:admin; gzip ile sıkıştırılır
    with open(os.path.join(comp, "Payload"), "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=6, mtime=0) as gz:
            write_cpio(root, gz)

    app_name = os.path.basename(app)
    with open(os.path.join(comp, "PackageInfo"), "w", encoding="utf-8") as fh:
        fh.write(f'''<?xml version="1.0" encoding="utf-8"?>
<pkg-info overwrite-permissions="true" relocatable="false" identifier="{PKG_ID}" postinstall-action="none" version="{version}" format-version="2" generator-version="beje-build_pkg" install-location="/Applications" auth="root">
    <payload numberOfFiles="{files}" installKBytes="{kbytes}"/>
    <bundle path="./{app_name}" id="{BUNDLE_ID}" CFBundleShortVersionString="{version}" CFBundleVersion="{version}"/>
    <bundle-version>
        <bundle id="{BUNDLE_ID}"/>
    </bundle-version>
    <upgrade-bundle>
        <bundle id="{BUNDLE_ID}"/>
    </upgrade-bundle>
    <update-bundle/>
    <atomic-update-bundle/>
    <strict-identifier>
        <bundle id="{BUNDLE_ID}"/>
    </strict-identifier>
    <relocate/>
</pkg-info>
''')
    shutil.rmtree(root)
    return kbytes


def write_distribution(flat, version, arch, kbytes):
    with open(os.path.join(flat, "Distribution"), "w", encoding="utf-8") as fh:
        fh.write(f'''<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
    <title>Bêje {version}</title>
    <welcome file="welcome.html" mime-type="text/html"/>
    <conclusion file="conclusion.html" mime-type="text/html"/>
    <options customize="never" require-scripts="false" rootVolumeOnly="true" hostArchitectures="{arch}"/>
    <domains enable_anywhere="false" enable_currentUserHome="false" enable_localSystem="true"/>
    <volume-check>
        <allowed-os-versions>
            <os-version min="11.0"/>
        </allowed-os-versions>
    </volume-check>
    <choices-outline>
        <line choice="default">
            <line choice="{PKG_ID}"/>
        </line>
    </choices-outline>
    <choice id="default"/>
    <choice id="{PKG_ID}" visible="false">
        <pkg-ref id="{PKG_ID}"/>
    </choice>
    <pkg-ref id="{PKG_ID}" version="{version}" onConclusion="none" installKBytes="{kbytes}">#beje.pkg</pkg-ref>
</installer-gui-script>
''')
    resources = os.path.join(flat, "Resources")
    os.makedirs(resources)
    for name, text in (("welcome.html", WELCOME), ("conclusion.html", CONCLUSION)):
        with open(os.path.join(resources, name), "w", encoding="utf-8") as fh:
            fh.write(text % {"version": version})


def write_xar(source_dir, output):
    """Klasörü xar arşivine yazar (macOS Installer'ın beklediği biçim, SHA-1 sağlama)."""
    entries = []  # (path, toc xml'i üretmek için düğüm)
    heap_files = []
    offset = 20  # yığının başında TOC'nin SHA-1 değeri durur
    next_id = [1]
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def node(path, name):
        file_id = next_id[0]
        next_id[0] += 1
        meta = (f"<name>{escape(name)}</name><ctime>{now}</ctime><mtime>{now}</mtime>"
                f"<uid>0</uid><user>root</user><gid>80</gid><group>admin</group>")
        if os.path.isdir(path):
            children = "".join(node(os.path.join(path, child), child) for child in sorted(os.listdir(path)))
            return f'<file id="{file_id}">{meta}<type>directory</type><mode>0755</mode>{children}</file>'
        nonlocal offset
        size = os.path.getsize(path)
        digest = sha1_file(path)
        heap_files.append(path)
        xml = (f'<file id="{file_id}">{meta}<type>file</type><mode>0644</mode><data>'
               f'<length>{size}</length><offset>{offset}</offset><size>{size}</size>'
               f'<encoding style="application/octet-stream"/>'
               f'<extracted-checksum style="sha1">{digest}</extracted-checksum>'
               f'<archived-checksum style="sha1">{digest}</archived-checksum>'
               f'</data></file>')
        offset += size
        return xml

    files_xml = "".join(node(os.path.join(source_dir, name), name) for name in sorted(os.listdir(source_dir)))
    toc = (f'<?xml version="1.0" encoding="UTF-8"?>\n<xar><toc>'
           f'<checksum style="sha1"><offset>0</offset><size>20</size></checksum>'
           f'<creation-time>{now}</creation-time>{files_xml}</toc></xar>').encode("utf-8")
    toc_compressed = zlib.compress(toc, 9)

    with open(output, "wb") as out:
        out.write(struct.pack(">4sHHQQI", b"xar!", 28, 1, len(toc_compressed), len(toc), 1))
        out.write(toc_compressed)
        out.write(hashlib.sha1(toc_compressed).digest())
        for path in heap_files:
            with open(path, "rb") as fh:
                shutil.copyfileobj(fh, out, 1 << 20)
    del entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--arch", required=True, choices=["arm64", "x86_64"])
    parser.add_argument("--mkbom", required=True)
    args = parser.parse_args()

    work = tempfile.mkdtemp(prefix="bejepkg-", dir=os.path.dirname(os.path.abspath(args.out)))
    try:
        kbytes = build_component(os.path.abspath(args.app), work, args.version, args.mkbom)
        flat = os.path.join(work, "flat")
        write_distribution(flat, args.version, args.arch, kbytes)
        write_xar(flat, args.out)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print("yazıldı:", args.out)


if __name__ == "__main__":
    main()
