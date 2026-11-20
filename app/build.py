"""构建 ModemSwitch.apk（无 Gradle，直接 aapt2 + javac + d8 + apksigner）"""
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
ENV = os.path.join(ROOT, '..', 'build_tools_env')
BT = os.path.join(ENV, 'bt', 'android-11')
PLAT = os.path.join(ENV, 'plat', 'android-11', 'android.jar')
JDK = os.path.join(ENV, 'jdk', 'jdk-17.0.2')
JAVA = os.path.join(JDK, 'bin', 'java.exe')
JAVAC = os.path.join(JDK, 'bin', 'javac.exe')
KEYTOOL = os.path.join(JDK, 'bin', 'keytool.exe')
AAPT2 = os.path.join(BT, 'aapt2.exe')
ZIPALIGN = os.path.join(BT, 'zipalign.exe')
D8JAR = os.path.join(BT, 'lib', 'd8.jar')
APKSIGNER = os.path.join(BT, 'lib', 'apksigner.jar')

OUT = os.path.join(ROOT, 'out')
env = dict(os.environ, JAVA_HOME=JDK)


def sh(argv, cwd=ROOT):
    print('>>', ' '.join(argv))
    r = subprocess.run(argv, cwd=cwd, env=env, capture_output=True)
    out = (r.stdout or b'').decode('utf8', 'replace') + (r.stderr or b'').decode('utf8', 'replace')
    if out.strip():
        txt = out.strip()[-3000:]
        enc = sys.stdout.encoding or 'utf-8'
        print(txt.encode(enc, 'replace').decode(enc, 'replace'))
    if r.returncode != 0:
        raise SystemExit('FAILED rc=%d' % r.returncode)


ASSETS = [('modem_1_tg_n.img', 't00_tg.img'),
          ('modem_1_wg_n.img', 'modem_1_wg_n.img.orig')]


def prepare_assets():
    """app/assets/ 里的固件即最终内置内容；缺失时才尝试从仓库外的 ../backup/ 拷入。

    原厂镜像原样内置：字节、长度、MD5 全部保持源文件本身，不做任何填充或改写。
    """
    a = os.path.join(ROOT, 'assets')
    os.makedirs(a, exist_ok=True)
    bak = os.path.join(os.path.dirname(ROOT), 'backup')
    for dst_name, src_name in ASSETS:
        dst = os.path.join(a, dst_name)
        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            print('asset keep', dst_name, os.path.getsize(dst),
                  hashlib.md5(open(dst, 'rb').read()).hexdigest())
            continue
        sf = os.path.join(bak, src_name)
        if os.path.exists(sf) and os.path.getsize(sf) > 0:
            shutil.copyfile(sf, dst)
            print('asset copy', src_name, '->', dst_name, os.path.getsize(dst),
                  hashlib.md5(open(dst, 'rb').read()).hexdigest())
        else:
            raise SystemExit('缺少固件 app/assets/%s：请放入原厂镜像'
                             '（或放到 %s）后重新构建' % (dst_name, sf))


def main():
    prepare_assets()
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    for d in ('classes', 'dex'):
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    res_zip = os.path.join(OUT, 'res.zip')
    sh([AAPT2, 'compile', '--dir', os.path.join(ROOT, 'res'), '-o', res_zip])

    base = os.path.join(OUT, 'base.apk')
    sh([AAPT2, 'link', '-o', base, '-I', PLAT, '--manifest',
        os.path.join(ROOT, 'AndroidManifest.xml'), '-A', os.path.join(ROOT, 'assets'),
        '--min-sdk-version', '17', '--target-sdk-version', '19', res_zip])

    src_dir = os.path.join(ROOT, 'src')
    javas = []
    for r, _, fs in os.walk(src_dir):
        javas += [os.path.join(r, f) for f in fs if f.endswith('.java')]
    sh([JAVAC, '-source', '8', '-target', '8', '-encoding', 'UTF-8',
        '-classpath', PLAT, '-d', os.path.join(OUT, 'classes')] + javas)

    classes = []
    for r, _, fs in os.walk(os.path.join(OUT, 'classes')):
        classes += [os.path.join(r, f) for f in fs if f.endswith('.class')]
    sh([JAVA, '-cp', D8JAR, 'com.android.tools.r8.D8', '--min-api', '17',
        '--output', os.path.join(OUT, 'dex')] + classes)

    dex = os.path.join(OUT, 'dex', 'classes.dex')
    with zipfile.ZipFile(base, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex, 'classes.dex')
    print('dex added', os.path.getsize(dex))

    ks = os.path.join(ROOT, 'my.jks')
    if not os.path.exists(ks):
        sh([KEYTOOL, '-genkeypair', '-keystore', ks, '-alias', 'a', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000', '-storepass', '123456',
            '-keypass', '123456', '-dname', 'CN=ModemSwitch'])

    aligned = os.path.join(OUT, 'aligned.apk')
    sh([ZIPALIGN, '-f', '4', base, aligned])
    final = os.path.join(ROOT, 'ModemSwitch.apk')
    sh([JAVA, '-jar', APKSIGNER, 'sign', '--ks', ks, '--ks-pass', 'pass:123456',
        '--key-pass', 'pass:123456', '--v1-signing-enabled', 'true',
        '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'false',
        '--min-sdk-version', '17', '--out', final, aligned])
    sh([JAVA, '-jar', APKSIGNER, 'verify', '--min-sdk-version', '17', final])
    print('APK:', final, os.path.getsize(final))


if __name__ == '__main__':
    main()