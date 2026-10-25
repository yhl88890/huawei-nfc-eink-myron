#!/usr/bin/env python3
"""build.py 的 Windows 版：用本机 SDK/JDK 直接出包，无 Gradle、无构建期下载。

跟 build.py 的差别只有三处：
1. d8 / aapt / zipalign / apksigner 换成 Windows 上的 .bat / .exe；
2. 不调用 native/build.sh（那是 Unix shell），原生库用 assets/native/ 里已有的预编译产物，
   但仍严格校验 build.sha256 —— 清单对不上就直接报错，避免打进过期的注入库；
3. 签名密钥固定放在 build/debug.keystore，出一次以后一直用它，后续才能覆盖安装。

用法：ANDROID_HOME=... JAVA_HOME=... python build-win.py
"""
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile
import tempfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
sdk = Path(os.environ["ANDROID_HOME"])
jdk = Path(os.environ["JAVA_HOME"])
bt = sdk / "build-tools/34.0.0"
android = sdk / "platforms/android-34/android.jar"
build_root = root / "build"
build_root.mkdir(parents=True, exist_ok=True)
build = Path(tempfile.mkdtemp(prefix="v2-", dir=build_root))
dist = root / "dist"
for directory in (build / "classes", build / "dex", dist):
    directory.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(jdk), PATH=str(jdk / "bin") + os.pathsep + os.environ.get("PATH", ""))

native_manifest = root / "assets/native/build.sha256"
if not native_manifest.exists():
    raise RuntimeError("缺少原生库清单 assets/native/build.sha256")
native_inputs = {"native/build.sh", "native/nci_rewrite.h", "native/hal_discovery.h", "native/hal_name.h",
                 "native/hook_debug.h", "native/owned_hook.h", "native/qpoll_hook.c", "native/qpoll_inject.c",
                 "assets/native/arm64-v8a/libqpollhook.so", "assets/native/arm64-v8a/qpoll-inject"}
manifest_lines = native_manifest.read_text().splitlines()
if {line.split(maxsplit=1)[1] for line in manifest_lines} != native_inputs:
    raise RuntimeError("原生库清单不完整，需要重新构建 native/build.sh")
for line in manifest_lines:
    expected, relative = line.split(maxsplit=1)
    source = (root / relative).resolve()
    if root not in source.parents and source != root:
        raise RuntimeError("原生库清单指向工程外：" + relative)
    if not source.exists():
        raise RuntimeError("原生库清单里的文件不存在（需要先构建原生层）：" + relative)
    if hashlib.sha256(source.read_bytes()).hexdigest() != expected:
        raise RuntimeError("原生库与源码不一致（过期）：" + relative)

libraries = []
for aar in sorted((root / "libs").glob("*-13.1.5.aar")):
    jar = build / (aar.stem + ".jar")
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    libraries.append(jar)
if len(libraries) != 4:
    raise RuntimeError("libs/ 需要固定的 Shizuku api/provider/aidl/shared 13.1.5 四个 AAR")


def run(*args, cwd=root):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)


run(jdk / "bin/javac", "-encoding", "UTF-8", "--release", "8",
    "-classpath", os.pathsep.join(str(p) for p in [android, *libraries]),
    "-d", build / "classes", *sorted((root / "src").rglob("*.java")))
run(jdk / "bin/jar", "cf", build / "classes.jar", "-C", build / "classes", ".")
run(bt / "d8.bat", "--min-api", "26", "--lib", android, "--output", build / "dex",
    build / "classes.jar", *libraries)
run(bt / "aapt.exe", "package", "-f", "-M", root / "AndroidManifest.xml", "-S", root / "res",
    "-A", root / "assets", "-I", android, "-F", build / "unsigned.apk")
with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(build / "dex/classes.dex", "classes.dex")
run(bt / "zipalign.exe", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
key = build_root / "debug.keystore"
if not key.exists():
    run(jdk / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "qpolllab", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=QPoll Lab Local Debug", "-noprompt")
version = ET.parse(root / "AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"]
apk = dist / ("QPollLab-" + version + ".apk")
run(bt / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "qpolllab", "--ks-pass", "pass:android",
    "--key-pass", "pass:android", "--out", apk, build / "aligned.apk")
run(bt / "apksigner.bat", "verify", "--verbose", apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(dist / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n", encoding="utf-8")
print("APK:", apk)
print("SHA256:", digest)
