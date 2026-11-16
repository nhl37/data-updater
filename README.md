# data-updater

一个本地数据库维护工具：按需从若干数据源同步记录，写入本地 SQLite 文件，供其他程序只读使用。

Java 17 + Swing，打成单个 jar，不需要服务端。

## 环境要求

- JDK 17
- Maven 3.8+
- Python 3.8+（可选，仅部分任务需要）

## 构建

开发调试用的包，未混淆、含依赖，文件名带 `pom.xml` 里的版本号：

```bash
mvn -DskipTests package
java -jar target/StockDataUpdater-1.0.1.jar
```

发布构建会多一步混淆，并清掉全部中转包，最后工程里**只留一个不带版本号的** `StockDataUpdater.jar`：

```bash
./build.sh
```

`build.sh` 把 fat jar、拆包后的 `app` / `lib`、混淆后的 `app-obf` 这些中转件全部放在系统临时目录（`$TMPDIR/sdu-build.*`），构建结束即删 —— 工程目录里自始至终不会出现第二个 jar，`target/` 下也不留包，只保留 `classes/` 供下次增量编译。收尾会断言整棵工程目录里只有一个 jar，且必须是不带版本号的那个，否则直接报错退出。

版本号只从 `pom.xml` 读，脚本里不写死：改 `<version>` 不需要动 `build.sh`。

## 运行

```bash
java -jar StockDataUpdater.jar --data-dir /path/to/data
```

| 参数 | 说明 |
|---|---|
| `--data-dir <目录>` | 数据根目录，不传则用 `<程序目录>/data` |
| `--backup-dir <目录>` | 改写数据库前的备份目录 |
| `--home <目录>` | 程序目录，配置与数据都挂在它下面 |

没有图形环境时进入控制台菜单。

## 配置

```bash
cp config.example.properties config.properties
```

`config.properties` 是**本机文件**，不进版本库，里面主要是本机路径、脚本位置与并发参数；没写的键一律使用内置默认值。

## 说明

程序本身不附带任何数据，只做本地搬运与格式转换。数据版权归各提供方所有，使用时请自行确认其服务条款。本项目不保证数据的准确性、完整性或可用性。

## 许可证

[MIT](LICENSE)
