# @author 雾晚
"""Verify the public artifact's exact ABI, compressed JNI payload and ARM64 ELF.

Default mode checks the standalone manager APK (core in the app): ARM64-only
native payload, the libgojni JNI bridge present, no duplicated module core.
Thin mode (--thin, B-route manager) checks the opposite contract: the manager
APK must carry no native libraries at all, because the core ships only inside
the root module.
"""
import struct
import sys
import zipfile

def verify(path, thin=False):
    with zipfile.ZipFile(path) as apk:
        native = [i for i in apk.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        if thin:
            assert not native, "thin manager APK must not contain native libraries"
            return
        assert {i.filename.split("/")[1] for i in native} == {"arm64-v8a"}, "unexpected public ABI"
        names = {i.filename for i in native}
        assert "lib/arm64-v8a/libgojni.so" in names, "manager JNI bridge is missing"
        assert not any(name.endswith("/librootbox.so") for name in names), "standalone module core duplicated in APK"
        for info in native:
            assert info.compress_type == zipfile.ZIP_DEFLATED, "JNI must remain compressed for extracted Root loading"
            with apk.open(info) as lib:
                header = lib.read(20)
            assert header[:4] == b"\x7fELF" and header[4] == 2 and header[5] == 1
            assert struct.unpack_from("<H", header, 18)[0] == 183, "native library is not ARM64"

if __name__ == "__main__":
    args = sys.argv[1:]
    thin = "--thin" in args
    for path in [a for a in args if a != "--thin"]:
        verify(path, thin=thin)
        if thin:
            print(f"Verified thin manager APK without native payload: {path}")
        else:
            print(f"Verified ARM64-only compressed manager JNI payload: {path}")
