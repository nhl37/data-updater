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
mkdir -p "$TMPDIR"
OUT_JAR="$BASE/StockDataUpdater.jar"
T=${TOOLS_DIR:-$HOME/.cache/tools}
M2=${M2_REPO:-$HOME/.m2/repository}
PGCP="$T/proguard-base.jar:$T/proguard-core.jar:$T/proguard-annotations.jar:$T/log4j-api.jar:$T/log4j-core.jar:$T/slf4j-api.jar:$T/log4j-slf4j2-impl.jar"

# 中转件（fat / original-瘦包 / app / lib / app-obf）全部落在工程之外的临时目录。
# 为什么不放 target/：放进工程就要在收尾时删，而删除在部分环境下会被「移入回收站」
# 接管（本机沙箱就是这样），结果是工程根下堆出一个 .Trash-0，每个中转包都留一份副本，
# 构建几次就几十上百 MB。—— 干脆让它们从头到尾不落在工程目录里。
# 收尾 mv -f 到临时目录也能保证「target/ 里不会有第二个 jar」，且不算删除动作。
WORK="$(mktemp -d "${TMPDIR%/}/sdu-build.XXXXXX")" || { echo "错误：无法创建临时构建目录（TMPDIR=$TMPDIR）" >&2; exit 1; }
mkdir -p "$WORK/stale"

# 把文件移出工程目录。跨文件系统时 mv 会顺手保留时间戳，在部分挂载点（overlay/tmpfs）
# 上会报「保留时间: 不允许的操作」—— 但内容其实已经搬过去、源也删掉了，纯属噪音。
# 所以吞掉 stderr，只认「源文件还在不在」这一个结果。
move_out() {
    [ -e "$1" ] || return 0
    mv -f "$1" "$2" 2>/dev/null || true
    if [ -e "$1" ]; then
        echo "错误：无法把 $(basename "$1") 移出工程目录" >&2
        return 1
    fi
}

# 上一轮 mvn 留在 target 里的包（手工跑过 mvn package 也会有），先挪出去再编，
# 否则下面「挑哪个才是本次产物」会挑到旧版本。
# 用 if 而不是 `[ -e f ] && move_out`：通配符没匹配时那个列表返回 1，
# `set -e` 会当成失败把脚本停在这里（首次构建必踩）。
for f in "$BASE"/target/*.jar; do
    if [ -e "$f" ]; then move_out "$f" "$WORK/stale/"; fi
done
# 旧版流程把混淆配置也生成在 target 里（现在生成到 $WORK），遗留的那份一并挪走
if [ -e "$BASE/target/proguard-run.cfg" ]; then
    move_out "$BASE/target/proguard-run.cfg" "$WORK/stale/"
fi

echo "[1/6] 独立编译（fat jar）"
mvn -o -q -Dmaven.repo.local="$M2" -Duser.home="$HOME" -DskipTests package
# 中转包名叫 StockDataUpdater-<pom 的 version>.jar。这里不写死版本号 ——
# 改 pom 的 version 时不必再回头改本脚本（写死过一次 1.0.0，改版本号就断路）。
# shade 还会额外留一份 original-*.jar（没拆依赖的瘦包），必须排除：
# 拿它启动会 NoClassDefFoundError，且它的名字同样随版本变，只能靠前缀区分。
FAT="$(ls -1 "$BASE"/target/StockDataUpdater-*.jar 2>/dev/null | grep -v '/original-' | head -1)"
[ -n "$FAT" ] && [ -f "$FAT" ] || { echo "错误：没生成 fat jar（target/StockDataUpdater-*.jar）" >&2; exit 1; }
echo "       中转包: $(basename "$FAT")"
for f in "$BASE"/target/original-*.jar; do
    if [ -e "$f" ]; then move_out "$f" "$WORK/stale/"; fi
done
move_out "$FAT" "$WORK/fat.jar"
FAT="$WORK/fat.jar"

echo "[2/6] 拆包：updater/ + uicons/ + defaults/ + python/ → app.jar，第三方 → lib.jar"
$PY - "$FAT" "$WORK/updater-app.jar" "$WORK/updater-lib.jar" <<'EOF'
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

echo "[3/6] ProGuard 混淆（u/ 重打包 + 独立字典）"
CFG="$WORK/proguard-run.cfg"
# 先把「@@BASE@@/target/xxx.jar」整段换成临时目录，剩下的 @@BASE@@ 才换回工程目录
# （混淆字典 obfuscation-dictionary.txt 仍在工程里，不能跟着搬家）。
sed -e "s|@@BASE@@/target/|$WORK/|g" -e "s|@@BASE@@|$BASE|g" "$BASE/proguard-updater.cfg" > "$CFG"
java -cp "$PGCP" proguard.ProGuard @"$CFG"

echo "[4/6] 合并 → 发布物"
# 上一轮的交付物先挪进临时目录再重写（不删：删除在本机会被转成「移入回收站」，
# 工程根下就会冒出 .Trash-0 并且每个副本都占 20 多 MB）。
move_out "$OUT_JAR" "$WORK/stale/StockDataUpdater.jar.prev"
$PY - "$WORK" "$OUT_JAR" <<'EOF'
import os, sys, zipfile
work, out = sys.argv[1], sys.argv[2]
srcs = [os.path.join(work, 'updater-lib.jar'),
        os.path.join(work, 'updater-app-obf.jar')]
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

echo "[5/6] 单包断言"
$PY "$BASE/tools/verify_isolation.py" --updater "$OUT_JAR"

echo "[6/6] 清理临时构建目录，只留唯一的交付物"
$PY - "$WORK" <<'EOF'
import shutil, sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
EOF

# 断言：整棵工程目录里只剩一个 jar，且必须是不带版本号的那个交付物。
# 带了版本号 → pom 没生效或中转件没扫干净；多了别的 jar → 有步骤把中转件落到了别处。
ALL_JARS=$(find "$BASE" \( -name '.git' -o -name '.Trash-*' \) -prune -o -name '*.jar' -print 2>/dev/null)
JAR_CNT=$(printf '%s\n' "$ALL_JARS" | grep -c . || true)
if [ "$JAR_CNT" != "1" ]; then
    echo "错误：构建后应只剩 1 个 jar，实际 $JAR_CNT 个：" >&2
    printf '%s\n' "$ALL_JARS" | sed 's/^/          /' >&2
    exit 1
fi
if [ "$(printf '%s\n' "$ALL_JARS" | head -1)" != "$OUT_JAR" ]; then
    echo "错误：唯一剩下的 jar 不是交付物 $OUT_JAR" >&2
    exit 1
fi
echo "        唯一产物：$OUT_JAR（$(du -h "$OUT_JAR" | cut -f1)）"
