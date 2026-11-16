# 任务

把开源项目 <https://github.com/nhl37/data-updater>（MIT，公开仓库）编译成这台机器上能跑起来的数据更新工具。

这份文档只给**目标、验收标准、已知的坑**，不规定动作顺序。目标机器的系统、网络、已装工具都不一样，照抄任何一条命令都可能失败——**怎么拿源码、走不走代理、用不用 git，由你按现场情况决定**。文中出现的命令都只是示例。

## 验收（三条都满足才算完成）

1. 源码树完整：有 `pom.xml`、`src/main/java/updater/`、`python/`。整包约 500KB，只有几 KB 或几十 KB 肯定是没下到。
2. `mvn -DskipTests package` 通过，产出 `target/StockDataUpdater-1.0.1.jar`（带依赖的 fat jar）。
3. 能启动：有桌面弹 GUI，无桌面进控制台菜单，例如
   `java -jar target/StockDataUpdater-1.0.1.jar --data-dir ./data`（数据目录不存在会自己建）。
   启动即报错退出不算完成。

## 交付物与挂载（名字固定）

- **带版本号的只有 Maven 产物**：`pom.xml` 的版本决定编译产物叫 `target/StockDataUpdater-1.0.1.jar`（版本号以 `pom.xml` 为准），验收看的就是这份；同目录 `original-` 开头的瘦包不带依赖，不能当工具用。若跑过 `./build.sh`，这些中转包会在收尾时被清理掉，`target/` 下不再有 jar —— 那是正常现象，需要时重跑 `mvn -DskipTests package` 即可重新生成。
- **最终交付/挂载文件不带版本号**：交出来的一定是名为 **`StockDataUpdater.jar`** 的可执行 jar（内容就是上面那份，只换个固定名字）。界面、插件、文档里出现的都是这个名字。发布构建完成后，整棵工程目录里应当**只有这一个 jar**。
- **挂载文件**：主程序「数据插件 → 添加插件」时选的就是这个 `StockDataUpdater.jar`；「检查」挂载的是**数据文件夹**，其中目录与文件名固定为 `db/`、`finance.db`、`stock_name.db`。
- 不要把 `updater-app.jar`、`updater-lib.jar` 这类拆包中间产物当成交付物。

## 不要做

- 不改 `pom.xml` 的依赖和版本、不改 `build.sh`、不改业务代码。唯一例外：`proguard-updater.cfg` 里的 JDK 绝对路径可以改，混淆本身是可选项。
- 不引入来源不明的源码包或可执行文件，只从项目仓库或它的镜像获取。
- 不用关闭校验（`http.sslVerify=false` 之类）、跳过编译错误的方式"跑通"。
- 不把本机配置和设备号（`config.properties`、`device.id`、`access.dat`）带进交付物。

## 需要你自己判断的三件事

1. **"命令返回 0"不等于下载成功。** 判定只认事实：文件在不在、大小对不对、解压后有 `pom.xml`。被拦截时经常返回 0 却下回来一个几十 KB 的 HTML 错误页。
2. **换路之前先想清楚上一条为什么失败。** 同一个办法最多试一次；镜像、代理这类东西随时失效，别在同一个地址上反复重试，也别死守一种办法。
3. **优先用本机已有的东西。** 有代理软件就用代理，没装 git 就下 zip，Windows 没有 `curl`/`unzip` 就用 PowerShell 或 Python，别为了凑某条命令先去装一堆东西。

示例（只示意形式，不是必须照着做）：

```bash
git clone --depth 1 https://github.com/nhl37/data-updater.git
curl -L -o src.zip https://github.com/nhl37/data-updater/archive/refs/heads/main.zip   # 不装 git 也能下
git -c 'url.https://gh-proxy.com/https://github.com/.insteadOf=https://github.com/' clone --depth 1 <仓库地址>  # 镜像前缀，只对本次生效
```

## 已知的坑

| 现象 | 大概率原因 | 思路 |
|---|---|---|
| clone 超时 / `Connection timed out` / `Could not resolve host` | GitHub 直连被拦 | 换镜像前缀、走本机代理、改成下 zip；都不通就换网络（手机热点） |
| 下载很快但文件只有几十 KB，解压没 `pom.xml` | 下回来的是错误页 | 换镜像或改用 git 重下 |
| clone "成功"但目录是空的（只有 `.git`） | 镜像缓存了旧状态 | 换另一个镜像或直连，别在原地反复 clone |
| `mvn` 依赖下载失败 / `repo.maven.apache.org` 连不上 | 中央仓库被限速 | 配阿里云镜像（`mirrorOf` 写 `central`），别改 pom 里的版本 |
| `Could not create local repository` | HOME 或权限不对 | `mvn -Dmaven.repo.local=$HOME/.m2/repository -Duser.home=$HOME …` |
| `pthread_create EAGAIN` | 内存小、核数被高估 | 限制 `-XX:ActiveProcessorCount=2 -Xmx768m` |
| 启动后中文乱码 | 默认编码不是 UTF-8 | `-Dfile.encoding=UTF-8` |
| `java -jar` 一启就 `NoClassDefFoundError` | 选了 `original-` 开头的瘦包，或拿了拆包中间产物 | 用带依赖的编译产物，最终命名 `StockDataUpdater.jar` |
| ProGuard 找不到 jmod | 配置里 JDK 路径写死 | 混淆可不做；要做就把路径改成 `$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")` |
| Windows 缺 `curl` / `unzip` | 系统工具不全 | PowerShell `Invoke-WebRequest`、`Expand-Archive`，或用 Python |

## 表里没有的问题，也要你解决

这份文档一定不全。遇到没写的情况：

1. 先判断是不是**环境问题**——路径、权限、编码、磁盘空间、防火墙、杀毒软件拦截、端口占用、代理残留变量，能自己修就修；
2. 再判断是不是**版本问题**——JDK 是否 17+、Maven 是否 3.6+、git 是否 2.x，缺什么装什么；
3. 修完继续往下走，不要停下来等人确认；
4. 只有确实需要外部信息（账号、付费软件、需要本人在场操作）才停下来问。

## 交付时说明

- 源码是怎么拿到的（clone / 镜像 / zip / 代理）
- 构建命令、编译产物路径（`target/StockDataUpdater-1.0.1.jar`）、最终交付文件名（`StockDataUpdater.jar`，不带版本号）、启动方式
- 挂载时选的文件名，以及数据文件夹里的 `db/`、`finance.db`、`stock_name.db` 是否齐了
- 遇到并解决了哪些问题，哪些没解决
