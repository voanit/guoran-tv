#!/usr/bin/env python3
"""使用已安装的 Android SDK/JDK 构建调试 APK，不下载 Gradle 或第三方依赖。"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def tool_home(explicit, variable):
    value = explicit or os.environ.get(variable) or os.environ.get('JAVA_HOME')
    if value:
        return Path(value).expanduser()
    executable = shutil.which('java')
    if executable:
        return Path(executable).resolve().parent.parent
    raise SystemExit('未找到 JDK，请设置 JAVA_HOME 或使用 --jdk / --javac')


def main():
    parser = argparse.ArgumentParser()
    default_sdk = Path.home() / ('Library/Android/sdk' if os.sys.platform == 'darwin' else 'Android/Sdk')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(default_sdk))))
    parser.add_argument('--jdk', help='运行 D8 和签名工具的 JDK 17+ 目录，默认 JAVA_HOME')
    parser.add_argument('--javac', default=os.environ.get('GUORAN_JAVAC'), help='javac 路径，可与运行工具的 JDK 不同')
    parser.add_argument('--work-dir', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    sdk, jdk = Path(args.sdk).expanduser(), tool_home(args.jdk, 'GUORAN_JAVA_HOME')
    javac = Path(args.javac).expanduser() if args.javac else jdk / 'bin/javac'
    tools = sdk / 'build-tools' / '34.0.0'
    android = sdk / 'platforms/android-34/android.jar'
    for required in [tools / 'aapt', tools / 'lib/d8.jar', tools / 'zipalign', tools / 'lib/apksigner.jar', android, javac]:
        if not required.is_file():
            raise SystemExit('缺少已安装构建工具：' + str(required))
    work = (args.work_dir or root / '.build').resolve()
    work.mkdir(parents=True, exist_ok=True)
    classes, gen, dex = [work / name for name in ['classes', 'generated', 'dex']]
    # 只清理本次构建生成目录；保留签名密钥，后续调试升级才能覆盖安装。
    for directory in [classes, gen, dex]:
        if directory.exists():
            if directory.is_symlink():
                raise SystemExit('构建目录不能是符号链接：' + str(directory))
            shutil.rmtree(directory)
        directory.mkdir()
    main_dir = root / 'app/src/main'
    unsigned, aligned = work / 'unsigned.apk', work / 'aligned.apk'
    output = root / 'guoran-tv-debug.apk'
    def run(command):
        subprocess.run([str(value) for value in command], check=True, cwd=root)
    run([tools / 'aapt', 'package', '-f', '-M', main_dir / 'AndroidManifest.xml', '-S', main_dir / 'res', '-A', main_dir / 'assets', '-I', android, '-J', gen, '-F', unsigned])
    sources = sorted((main_dir / 'java').rglob('*.java')) + sorted(gen.rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', android, '-d', classes] + sources)
    jar = work / 'classes.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes).as_posix())
    run([jdk / 'bin/java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '21', '--lib', android, '--output', dex, jar])
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
        for path in dex.glob('classes*.dex'):
            archive.write(path, path.name)
    run([tools / 'zipalign', '-f', '4', unsigned, aligned])
    keystore = work / 'guoran-debug.keystore'
    if not keystore.exists():
        run([jdk / 'bin/keytool', '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android', '-alias', 'guoran-debug', '-dname', 'CN=Guoran TV Development,O=Local Development,C=CN', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-storetype', 'JKS'])
        keystore.chmod(0o600)
    run([jdk / 'bin/java', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', keystore, '--ks-key-alias', 'guoran-debug', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned])
    run([jdk / 'bin/java', '-jar', tools / 'lib/apksigner.jar', 'verify', output])
    print('调试 APK：' + str(output))


if __name__ == '__main__':
    main()
