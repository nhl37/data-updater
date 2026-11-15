#!/bin/bash
# 数据更新程序独立发布构建
# ---------------------------------------------------------------------------
# 源码根：src/main/java（包 updater.*），依赖只有第三方库。
# 全过程不读主程序的 jar / classes / business.* 类；产出 StockDataUpdater.jar。
#
# 本工程与主程序 StockSearch 是**同级**两个目录，彼此不嵌套：
#   项目根/StockSearch/       主程序（只读本地库）
#   项目根/data-updater/      本工程（抓取与更新）
# 产物落在本工程根，主程序侧的 package_updater_jar.sh 只是转发入口。
#
# 用法：./build.sh  （或 StockSearch 根的 ../package_updater_jar.sh）
# 依赖：$TOOLS_DIR（默认 ~/.cache/tools）下的 ProGuard、JDK 17 jmods、$M2_REPO（默认 ~/.m2）本地仓库
set -e
cd "$(dirname "$0")"
BASE="$PWD"
PY=${PY:-python3}
export TMPDIR=${TMPDIR:-$HOME/.cache/tmp}
OUT_JAR="$BASE/StockDataUpdater.jar"
T=${TOOLS_DIR:-$HOME/.cache/tools}
M2=${M2_REPO:-$HOME/.m2/repository}
PGCP="$T/proguard-base.jar:$T/proguard-core.jar:$T/proguard-annotations.jar:$T/log4j-api.jar:$T/log4j-core.jar:$T/slf4j-api.jar:$T/log4j-slf4j2-impl.jar"

echo "[1/5] 独立编译（fat jar）"
mvn -o -q -Dmaven.repo.local="$M2" -Duser.home="$HOME" -DskipTests package
FAT="$BASE/target/StockDataUpdater-1.0.0.jar"
[ -f "$FAT" ] || { echo "错误：没生成 $FAT" >&2; exit 1; }

echo "[2/5] 拆包：updater/ + uicons/ + defaults/ + python/ → app.jar，第三方 → lib.jar"
$PY - "$FAT" "$BASE/target/updater-app.jar" "$BASE/target/updater-lib.jar" <<'EOF'
import sys, zipfile, os
fat, app, lib = sys.argv[1:4]
for p in (app, lib):
    if os.path.exists(p): os.remove(p)
# defaults/ 是自家的出厂常量（东财接口地址等），python/ 是随包的 python 脚本
# （除权读 gbbq、财报、股本各一份），它们都跟 updater/ 一起走 app.jar；
# 不能落到 lib.jar，否则"第三方库"里混进自家资源，以后没人认得出该不该动。
with zipfile.ZipFile(fat) as z, \
     zipfile.ZipFile(app, 'w', zipfile.ZIP_DEFLATED) as za, \
     zipfile.ZipFile(lib, 'w', zipfile.ZIP_DEFLATED) as zl:
    for n in z.namelist():
        if n.endswith('/'):
            continue
        data = z.read(n)
        (za if (n.startswith('updater/') or n.startswith('uicons/')
                or n.startswith('defaults/')
                or n.startswith('python/')) else zl).writestr(n, data)
print('   app:', len(zipfile.ZipFile(app).namelist()), ' lib:', len(zipfile.ZipFile(lib).namelist()))
EOF

echo "[3/5] ProGuard 混淆（u/ 重打包 + 独立字典）"
CFG="$BASE/target/proguard-run.cfg"
sed "s|@@BASE@@|$BASE|g" "$BASE/proguard-updater.cfg" > "$CFG"
java -cp "$PGCP" proguard.ProGuard @"$CFG"

echo "[4/5] 合并 → 发布物"
$PY - "$BASE" "$OUT_JAR" <<'EOF'
import os, sys, zipfile
base, out = sys.argv[1], sys.argv[2]
srcs = [os.path.join(base, 'target/updater-lib.jar'),
        os.path.join(base, 'target/updater-app-obf.jar')]
if os.path.exists(out): os.remove(out)
mf = ("Manifest-Version: 1.0\r\n"
      "Main-Class: updater.main.updater.UpdaterMain\r\n"
      "Created-By: data-updater/build.sh\r\n\r\n")
services = {}
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
    zo.writestr(zipfile.ZipInfo('META-INF/MANIFEST.MF'), mf)
    seen = set()
    for s in srcs:
        with zipfile.ZipFile(s) as z:
            for n in z.namelist():
                if n in seen or n.endswith('/'): continue
                if n == 'META-INF/MANIFEST.MF' or n.endswith(('SF','DSA','RSA')): continue
                if n.endswith('module-info.class'): continue
                if n.startswith('META-INF/services/'):
                    seen.add(n)
                    got = services.setdefault(n, [])
                    for line in z.read(n).decode('utf-8', 'ignore').splitlines():
                        line = line.strip()
                        if line and not line.startswith('#') and line not in got:
                            got.append(line)
                    continue
                if n.startswith('META-INF/') and not n.startswith('META-INF/maven/'):
                    continue
                # 自家的构建坐标不进发布物：META-INF/maven/<groupId>/ 会把 groupId
                # 和整份 pom 带出来，第三方库的 maven 元数据照常保留（许可文件在里面）
                if n.startswith('META-INF/maven/com.datatools.updater/'):
                    continue
                seen.add(n)
                zo.writestr(n, z.read(n))
    for n, lines in services.items():
        zo.writestr(n, '\n'.join(lines) + '\n')
print('   发布物:', out, os.path.getsize(out)//1024//1024, 'MB')
EOF

echo "[5/5] 单包断言"
$PY "$BASE/tools/verify_isolation.py" --updater "$OUT_JAR"
echo "完成：$OUT_JAR"
