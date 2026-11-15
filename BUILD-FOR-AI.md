# 构建

需要 JDK 17 + Maven。源码在 <https://github.com/nhl37/data-updater>（公开仓库，MIT）。

## 第 1 步：拿到源码

GitHub 在国内常常连不上，这跟项目本身无关。下面四条路**任意一条成功即可**。

硬规则：每条最多试 1 次、网络超时按 15 秒；失败立刻换下一条，不要反复重试同一条命令，也不要因为下不到源码就改构建命令。

**怎么算拿到了**：目录里同时有 `pom.xml` 和 `src/main/java/updater/`。只下到一个几十 KB 的文件、或解压出来只有 README，都是没拿到（下回来的是错误页），换下一条。

### 路 1：直接 clone（GitHub 能连上就走这条）

```bash
git clone --depth 1 https://github.com/nhl37/data-updater.git && cd data-updater
```

### 路 2：git 挂镜像加速（clone 命令一个字都不用改）

镜像站随时会失效，先探测，再用返回 200 的那个：

```bash
for m in https://gh-proxy.com/https://github.com/ https://ghfast.top/https://github.com/; do
  printf '%s ' "$m"
  curl -s -o /dev/null -w '%{http_code}\n' --max-time 8 \
    "${m}nhl37/data-updater.git/info/refs?service=git-upload-pack"
done
```

用探测到的那个克隆（`-c` 只作用于这一条命令，不改全局 git 配置）：

```bash
git -c 'url.https://gh-proxy.com/https://github.com/.insteadOf=https://github.com/' \
    clone --depth 1 https://github.com/nhl37/data-updater.git && cd data-updater
```

### 路 3：机器上装了代理（Clash / v2ray 等）

先找可用代理（`HTTPS_PROXY` 环境变量，或 7890 / 10809 / 1080 这类本地端口）：

```bash
for p in "${HTTPS_PROXY:-}" "${https_proxy:-}" http://127.0.0.1:7890 http://127.0.0.1:10809 http://127.0.0.1:1080; do
  [ -z "$p" ] && continue
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 -x "$p" https://github.com)
  if [ "$code" = 200 ] || [ "$code" = 301 ] || [ "$code" = 302 ]; then echo "可用代理: $p"; break; fi
done
```

```bash
git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 \
    clone --depth 1 https://github.com/nhl37/data-updater.git && cd data-updater
```

没装代理软件就跳过这条。

### 路 4：不装 git 也能下（直接下 zip）

```bash
curl -L --max-time 60 -o src.zip https://codeload.github.com/nhl37/data-updater/zip/refs/tags/v1.0.0
```

连不上就换镜像前缀，后面的路径不变：

```bash
curl -L --max-time 60 -o src.zip https://gh-proxy.com/https://github.com/nhl37/data-updater/archive/refs/tags/v1.0.0.zip
curl -L --max-time 60 -o src.zip https://ghfast.top/https://github.com/nhl37/data-updater/archive/refs/tags/v1.0.0.zip
```

```bash
unzip -q src.zip && cd data-updater-1.0.0
```

Windows 没有 `unzip`：右键解压，然后进 `data-updater-1.0.0` 目录。

## 第 2 步：构建

源码树就位后（当前目录是 `data-updater` 或 `data-updater-1.0.0`），后续与源码怎么来的无关：

```bash
mvn -DskipTests package
cp config.example.properties config.properties
java -jar target/StockDataUpdater-1.0.0.jar --data-dir /path/to/data
```

有桌面弹 GUI，无桌面进控制台菜单，都算成功。

## 依赖下载也连不上

源码之外的第二个网络卡点是 Maven 中央仓库 / PyPI。给 `~/.m2/settings.xml` 配阿里云镜像（`mirrorOf` 写 `central`），
Python 依赖加 `-i https://pypi.tuna.tsinghua.edu.cn/simple`。别因为下载慢去改 `pom.xml` 里的依赖版本。

## 混淆版（可选）

ProGuard 7.x 的 jar 放 `~/.cache/tools/`：`proguard-base` `proguard-core` `proguard-annotations` `log4j-api` `log4j-core` `slf4j-api` `log4j-slf4j2-impl`。

`proguard-updater.cfg` 里 JDK 路径写死了，先改：

```bash
JDK_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
sed -i "s#/usr/lib/jvm/java-17-openjdk-amd64#$JDK_HOME#g" proguard-updater.cfg
./build.sh
```

产物 `StockDataUpdater.jar`。

## 报错

| 报错 | 处理 |
|---|---|
| `Failed to connect to github.com` / `Could not resolve host` / `Connection timed out` | 换路 2 / 3 / 4，别重试同一条 |
| `src.zip` 只有几十 KB、解压出来没有 `pom.xml` | 下回来的是错误页，换镜像重下 |
| `Could not create local repository` | `mvn -Dmaven.repo.local=$HOME/.m2/repository -Duser.home=$HOME -DskipTests package` |
| 依赖下载超时 / `repo.maven.apache.org` 连不上 | 配阿里云镜像，见「依赖下载也连不上」 |
| `pthread_create EAGAIN` | `export MAVEN_OPTS="-XX:ActiveProcessorCount=2 -Xmx768m"` |
| ProGuard 找不到 jmod | 上面的 sed 没执行 |
| 中文乱码 | `java -Dfile.encoding=UTF-8 -jar ...` |

Python 脚本需要 `pytdx pandas pytdxdata akshare`，缺哪个只影响对应任务。
