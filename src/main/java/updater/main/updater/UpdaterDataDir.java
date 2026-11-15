package updater.main.updater;

import updater.mount.DataRoot;
import updater.mount.MountConfig;

import java.io.File;
import java.io.IOException;

/**
 * 数据更新器写数据的落点（「数据文件夹」）。
 *
 * <p><b>只认启动参数</b>：更新器通常由别的程序拉起，数据目录由调用方通过
 * {@code --data-dir} 传进来，这里不再读 {@code config.properties} / {@code mount.properties}。
 * 更新器独立分发以后，它自己反推出来的目录和调用方挂载的目录很容易分家 ——
 * 2026-11-06 就出过一次：更新器把 5218 只全部新建进 {@code <程序目录>/data/db}，
 * 调用方挂载的库一条数据都没进。</p>
 *
 * <p>调用方没传参数时（直接双击 jar 调试）退回 {@code <程序目录>/data}，
 * 界面上的来源会显示「默认位置（未传参数）」。</p>
 *
 * <p>目录结构固定，与 {@link DataRoot} 的识别规则对称：</p>
 *
 * <pre>
 * &lt;数据文件夹&gt;/
 *   db/sh600000.db …   个股行情库
 *   finance.db         财报库
 *   stock_name.db      名称库
 * </pre>
 *
 * <p>生效方式：把数据文件夹塞进 {@link MountConfig#setDataRootOverride(String)}，
 * 之后 {@code MountConfig.currentDbDir()} / {@code currentNameDbFile()} /
 * {@code currentFinanceDbFile()} 全部按它推导 —— 整条更新链路（盘后下载、网络抓价、
 * 补历史、除权、财报、分钟线、名称）一处都不用改。</p>
 */
public final class UpdaterDataDir {

    /** 启动参数传进来的数据文件夹（规范化后的绝对路径；空串 = 没传） */
    private static String argRoot = "";

    /** 当前数据文件夹的来源 */
    public enum Source {
        /** 调用方用 {@code --data-dir} 传进来的 */
        ARGUMENT("启动参数传入"),
        /** 没传参数，用程序目录下的 data */
        DEFAULT("默认位置（未传参数）");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /** 界面显示用的一行说明 */
        public String label() {
            return label;
        }
    }

    private UpdaterDataDir() {
    }

    /** 程序目录下的兜底数据文件夹（{@code <程序目录>/data}） */
    public static String defaultRoot() {
        return new File(UpdaterMain.currentHome(), "data").getAbsolutePath();
    }

    /**
     * 记录启动参数里的数据目录（空串 / null = 没传），返回规范化后的值。
     *
     * <p>相对路径按程序目录展开：参数的语义是「哪个文件夹」，写成 {@code data}
     * 也应该落在程序目录下，而不是 JVM 当时碰巧的工作目录。</p>
     */
    public static String setFromArgs(String raw) {
        argRoot = normalize(raw);
        if (!argRoot.isEmpty()) {
            File file = new File(argRoot);
            if (!file.isAbsolute()) {
                argRoot = file.getAbsolutePath().replace('\\', '/');
            }
        }
        return argRoot;
    }

    /** 调用方是否传了数据目录 */
    public static boolean provided() {
        return !argRoot.isEmpty();
    }

    /** 当前生效的数据文件夹：启动参数优先，没传就用程序目录下的 data */
    public static String root() {
        return argRoot.isEmpty() ? defaultRoot() : argRoot;
    }

    /** 当前数据文件夹的来源 */
    public static Source source() {
        return argRoot.isEmpty() ? Source.DEFAULT : Source.ARGUMENT;
    }

    /** 当前生效的股票库目录（{@code <数据文件夹>/db}） */
    public static String stockDir() {
        return DataRoot.stockDirOf(root());
    }

    /**
     * 让数据文件夹立即生效：建好目录 + 设置覆盖值。
     *
     * <p>先 {@link #ensure()} 再设覆盖值：目录不可用时覆盖值不该被设成一个不存在的位置，
     * 否则后面每个任务都会在错的地方建库。</p>
     */
    public static Paths apply() throws IOException {
        Paths paths = ensure();
        MountConfig.setDataRootOverride(paths.root);
        return paths;
    }

    /**
     * 创建数据文件夹（不存在就建），并补齐库文件。
     *
     * <p>参数指向的目录就是调用方给的位置，缺什么补什么；
     * 只有一种情况要报错：路径本身是个文件，或者目录建不出来（没权限 / 盘符不存在）。</p>
     *
     * <p>{@code finance.db} 会建一个空文件：财报库是「挂载即用」，空文件由 SQLite
     * 首次打开时自动建表。名称库不用建，{@code StockNameStore} 首次写入会自建表。</p>
     */
    public static Paths ensure() throws IOException {
        Paths paths = new Paths();
        paths.root = root();
        paths.source = source();

        File rootFile = new File(paths.root);
        if (rootFile.exists() && !rootFile.isDirectory()) {
            throw new IOException("数据文件夹路径是个文件，不是文件夹：\n" + paths.root
                    + "\n\n请检查启动参数 --data-dir 指向的数据目录。");
        }

        paths.rootCreated = mkdirs(rootFile, "数据文件夹");
        paths.existedBefore = !paths.rootCreated;

        paths.stockDir = DataRoot.stockDirOf(paths.root);
        File stockFile = new File(paths.stockDir);
        paths.stockDirCreated = mkdirs(stockFile, "股票库目录");

        File finance = new File(DataRoot.financeFileOf(paths.root));
        paths.financeDb = finance.getPath();
        File financeParent = finance.getParentFile();
        if (financeParent != null) {
            mkdirs(financeParent, "数据文件夹");
        }
        if (!finance.isFile()) {
            try {
                paths.financeDbCreated = finance.createNewFile();
            } catch (IOException e) {
                // 建不出空库不算致命：真到下载财报那一步会给出明确的「文件不存在」
                System.out.println("预建财报库文件失败（不影响行情类任务）: " + e.getMessage());
            }
        }

        paths.nameDb = DataRoot.nameFileOf(paths.root);
        paths.stockCount = DataRoot.countStockDbs(stockFile);
        return paths;
    }

    /** 去掉引号、统一 / 分隔、去尾部斜杠（参数里写 Windows 路径也不出错） */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        value = value.replace('\\', '/');
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 生效后的路径快照 */
    public static final class Paths {
        /** 数据文件夹 */
        public String root = "";
        /** 这个文件夹是怎么来的（启动参数 / 默认） */
        public Source source = Source.DEFAULT;
        /** 股票库目录（数据文件夹下的 db） */
        public String stockDir = "";
        /** 财报库文件 */
        public String financeDb = "";
        /** 名称库文件 */
        public String nameDb = "";
        /** 数据文件夹是这次新建的 */
        public boolean rootCreated;
        /** 股票库目录是这次新建的 */
        public boolean stockDirCreated;
        /** 财报库空文件是这次新建的 */
        public boolean financeDbCreated;
        /** 运行前数据文件夹是否已存在 */
        public boolean existedBefore;
        /** 股票库目录里的个股库数量 */
        public int stockCount;

        /** 有没有新建目录（界面据此提示「已创建」） */
        public boolean createdAnything() {
            return rootCreated || stockDirCreated;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("数据文件夹: ").append(root).append("（").append(source.label()).append("）");
            if (rootCreated) {
                sb.append("（已创建）");
            }
            sb.append("\n股票库目录: ").append(stockDir).append("（").append(stockCount).append(" 个库）");
            if (stockDirCreated) {
                sb.append("（已创建，等待「补最新价格」建库）");
            }
            return sb.toString();
        }
    }

    private static boolean mkdirs(File dir, String what) throws IOException {
        if (dir == null || dir.isDirectory()) {
            return false;
        }
        if (dir.mkdirs() || dir.isDirectory()) {
            System.out.println("已创建" + what + ": " + dir.getAbsolutePath());
            return true;
        }
        throw new IOException("无法创建" + what + ": " + dir.getAbsolutePath()
                + "\n请检查路径是否正确、是否有写权限（或用 --data-dir 换一个数据文件夹）。");
    }
}
