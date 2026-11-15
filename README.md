# data-updater

一个本地数据库维护工具：按需从若干数据源同步记录，写入本地 SQLite 文件，供其他程序只读使用。

Java 17 + Swing，打成单个 jar，不需要服务端。

## 环境要求

- JDK 17
- Maven 3.8+
- Python 3.8+（可选，仅部分任务需要）

## 构建

```bash
mvn -DskipTests package
java -jar target/StockDataUpdater-1.0.0.jar
```

发布构建会多一步混淆，产出 `StockDataUpdater.jar`：

```bash
./build.sh
```

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
