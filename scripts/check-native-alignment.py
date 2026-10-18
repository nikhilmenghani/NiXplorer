"""Check 64-bit ELF LOAD segment alignment in an APK (no third-party dependencies).
Usage: python scripts/check-native-alignment.py path/to/app.apk
Also run Android build-tools zipalign -c -P 16 -v 4 path/to/app.apk.
"""
import struct
import sys
import zipfile

failures = []
checked = 0
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in archive.namelist():
        if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        data = archive.read(name)
        if data[:5] != b"\x7fELF\x02":
            failures.append(name + ": expected 64-bit ELF")
            continue
        order = "<" if data[5] == 1 else ">"
        offset = struct.unpack_from(order + "Q", data, 32)[0]
        stride, count = struct.unpack_from(order + "HH", data, 54)
        segments = []
        for index in range(count):
            start = offset + index * stride
            if struct.unpack_from(order + "I", data, start)[0] == 1:
                file_offset, address = struct.unpack_from(order + "QQ", data, start + 8)
                alignment = struct.unpack_from(order + "Q", data, start + 48)[0]
                segments.append(alignment)
                if alignment < 16384 or file_offset % 16384 != address % 16384:
                    failures.append(name + ": LOAD alignment " + hex(alignment))
        if not segments:
            failures.append(name + ": no LOAD segments")
        checked += 1
        print(name + ": " + ", ".join(hex(value) for value in segments))
if checked == 0:
    failures.append("No 64-bit native libraries checked")
for failure in failures:
    print("FAIL " + failure)
if failures:
    sys.exit(1)
print("PASS: all " + str(checked) + " 64-bit native libraries support 16 KB segment alignment")
