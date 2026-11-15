package updater.main.updater;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.Scanner;

/**
 * 数据更新程序的独立入口 —— 单独打成 StockDataUpdater.jar，可独立运行。
 *
 * <p>启动方式与行为：
 * <pre>
 *   java -jar StockDataUpdater.jar --data-dir &lt;数据目录&gt; [--backup-dir &lt;备份目录&gt;]
 *                                  [--home &lt;程序目录&gt;] [--user-id &lt;登录用户ID&gt;]
 *                                           # 图形环境: 打开更新画面（按数据类别分组，含股东 / 风险 / 估值 / 披露日）
 *                                           # 无显示环境: 控制台菜单
 *   java -jar StockDataUpdater.jar --data-dir D:/StockData 1
 *                                           # 直接执行: 1盘后 2网络最新价 3除权 4财报 5分钟线 6股票名称 7补充历史
 *                                           # 股东 / 风险 / 估值 / 披露日走图形界面的任务，需台账与进度
 * </pre>
 *
 * <p><b>数据目录只从参数取</b>：外部程序启动时自动传 {@code --data-dir} /
 * {@code --backup-dir}，更新器不再读 config.properties / mount.properties 去猜。
 * 没传参数时（直接双击调试）退回 {@code <程序目录>/data}。</p>
 *
 * <p>{@code --user-id} 只为兼容既有调用方而接受，程序本身不再使用它。</p>
 *
 * <p><b>程序文件夹自动定位</b>：启动参数 {@code --home} 优先（调用方拉起子进程时直接传自己的目录）；
 * 否则看当前目录有没有 {@code config.properties}，没有就改用 jar 所在目录、再退到 jar 的上一级、
 * 再退到「上次记住的程序文件夹」（存在 {@code ~/.datatools/updater-home.txt}，jar 被挪走以后
 * 还能找回来）。四处都没有就用当前目录继续，不再弹框退出 —— 接口地址读 jar 内置默认值，
 * 数据目录取 {@code <程序目录>/data}，python 解释器在「设置」里选。</p>
 */
public class UpdaterMain {

    public static void main(String[] args) {
        // 被重启拉起时先等老进程退净
        updater.util.AppRestart.awaitPreviousExit();
        // 参数先拆出来：--data-dir / --backup-dir / --home 是运行参数，剩下才是任务编号
        Startup startup = parseArgs(args);
        resolveProgramFolder(startup.home);
        UpdaterDataDir.setFromArgs(startup.dataDir);
        BackupDir.setFromArgs(startup.backupDir);
        // 本地数据来源目录由本程序自己配（updater.tdx.path），不读外部的 tdx.path
        updater.fetchdata.tdx.TdxDataDir.useUpdaterScope();
        // 数据目录按参数生效（没传参数则用程序目录下的 data）
        applyDataDir();
        if (!startup.tasks.isEmpty()) {
            for (String task : startup.tasks) {
                runTask(task);
            }
            return;
        }
        // 有显示器时打开更新画面；无显示（服务器/定时任务）时用控制台菜单
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    updater.ui.UpdaterUi.install();
                    UpdaterFrame frame = new UpdaterFrame();
                    updater.ui.UpdaterIcon.install(frame);
                    frame.setVisible(true);
                }
            });
            return;
        }
        // 交互菜单
        Scanner scanner = new Scanner(System.in);
        while (true) {
            printMenu();
            String line = scanner.hasNextLine() ? scanner.nextLine() : null;
            if (line == null) {
                break;
            }
            String choice = line.trim();
            if (choice.isEmpty()) {
                continue;
            }
            if ("0".equals(choice) || "q".equalsIgnoreCase(choice) || "exit".equalsIgnoreCase(choice)) {
                System.out.println("再见。");
                return;
            }
            runTask(choice);
        }
    }

    /**
     * 当前程序文件夹 —— {@code config/}、{@code data/}、{@code logs/} 及所有相对路径都挂在它上面。
     *
     * <p>启动时由 {@link #resolveProgramFolder()} 定好：当前目录没有 {@code config.properties}
     * 就自动改用 jar 所在目录、再退到 jar 的上一级；三处都没有才用当前目录。</p>
     */
    public static String currentHome() {
        // 单一真源：ProgramHome 由启动参数 / jar 位置 / 上次记录定好后就不再变。
        // 不能直接读 user.dir —— 运行期改属性对相对路径无效，两处口径容易分家
        return updater.cfg.ProgramHome.path();
    }

    /**
     * 拆启动参数：{@code --data-dir} / {@code --backup-dir} / {@code --home} / {@code --user-id}
     * 支持「空格分隔」和「=」两种写法，其余参数按任务编号处理（1~7）。
     */
    private static Startup parseArgs(String[] args) {
        Startup startup = new Startup();
        if (args == null) {
            return startup;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i] == null ? "" : args[i].trim();
            if (arg.isEmpty()) {
                continue;
            }
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if ("--data-dir".equals(name) || "--backup-dir".equals(name)
                    || "--home".equals(name) || "--user-id".equals(name)) {
                if (value == null && i + 1 < args.length) {
                    value = args[++i];
                }
                if ("--data-dir".equals(name)) {
                    startup.dataDir = value == null ? "" : value;
                } else if ("--backup-dir".equals(name)) {
                    startup.backupDir = value == null ? "" : value;
                } else if ("--home".equals(name)) {
                    startup.home = value == null ? "" : value;
                }
                // --user-id 由外部宿主传入，这里只吞掉它的值，不做任何用途
                continue;
            }
            if (arg.startsWith("--")) {
                System.out.println("忽略未知参数: " + arg);
                continue;
            }
            startup.tasks.add(arg);
        }
        return startup;
    }

    /** 一次启动用到的参数 */
    private static final class Startup {
        String dataDir = "";
        String backupDir = "";
        /** 程序文件夹（调用方拉起子进程时传自己的目录）；空 = 没传 */
        String home = "";
        final java.util.List<String> tasks = new java.util.ArrayList<String>();
    }

    /**
     * 让「数据文件夹」生效：设进覆盖位（之后 currentDbDir / 名称库 / 财报库全按它推导），
     * 并把缺的目录建出来。
     *
     * <p>失败不阻断启动：界面上那一行会显示目录不可用，点更新时会再试一次并给出
     * 明确原因（无权限 / 盘符不存在），比启动就退出好排查。</p>
     */
    private static void applyDataDir() {
        try {
            UpdaterDataDir.Paths paths = UpdaterDataDir.apply();
            System.out.println("数据文件夹: " + paths.root + "（" + paths.source.label() + "）"
                    + (paths.rootCreated ? "（已创建）" : ""));
            System.out.println("股票库目录: " + paths.stockDir + "（" + paths.stockCount + " 个库）");
            System.out.println("财报库文件: " + paths.financeDb);
            System.out.println("名称库文件: " + paths.nameDb);
            // 除权 / 补历史都读这个目录，打出来省得用户翻 config.properties 猜程序用的是哪个
            String localSource = updater.fetchdata.tdx.TdxDataDir.configuredPath();
            if (localSource.isEmpty()) {
                System.out.println("本地数据来源: 未设置（在右上「路径设置 → 本地数据来源」里选行情软件安装目录）");
            } else {
                java.io.File dir = new java.io.File(localSource);
                System.out.println("本地数据来源: " + localSource
                        + (dir.isDirectory() ? "" : "（目录不存在，请在「路径设置」里重新选择）"));
            }
        } catch (Exception e) {
            System.out.println("数据文件夹不可用: " + e.getMessage());
        }
    }

    /**
     * 确定「程序文件夹」，让相对路径有一个确定的落点。
     *
     * <p>顺序：{@code --home} 参数 → 当前目录 → jar 所在目录 → jar 的上一级目录 →
     * 上次记住的程序文件夹。安装包里 jar 在 {@code app/}、{@code config.properties}
     * 在上层目录，双击 {@code app/StockDataUpdater.jar} 也能自动找到程序文件夹。</p>
     *
     * <p><b>{@code --home} 放在第一位</b>：被别的程序拉起时可以把 {@code --home} 传成那个程序的目录，
     * 这样 jar 被挪到任何地方都还知道程序文件夹在哪。单独双击时靠后两条兜底：
     * jar 挪走以后，「上次记住的程序文件夹」把 {@code config.properties}、
     * {@code python/} 这些相对资源接回来（否则除权任务会因为找不到 python 脚本直接失败）。</p>
     *
     * <p>四处都没有 {@code config.properties} 时<b>不再退出</b>：配置项缺失
     * 已经各有默认值（接口地址读 jar 内置、数据目录取 {@code <程序目录>/data}、
     * python 脚本用 jar 内内置的那份），硬拦下来只会让「双击就能看看」变成「双击就报错」。
     * 这里只提示一次，用当前目录继续跑。</p>
     */
    private static void resolveProgramFolder(String explicitHome) {
        // ① 启动参数 --home：调用方直接告诉我们程序文件夹在哪
        if (explicitHome != null && !explicitHome.trim().isEmpty()) {
            java.io.File dir = new java.io.File(explicitHome.trim());
            if (dir.isDirectory() && new java.io.File(dir, "config.properties").isFile()) {
                applyHome(dir, "启动参数 --home");
                return;
            }
            System.out.println("--home 指向的目录不可用（不存在或没有 config.properties），继续自动查找: "
                    + dir.getAbsolutePath());
        }
        // ② 当前目录已经有配置文件：本来就是程序文件夹
        if (new java.io.File("config.properties").isFile()) {
            rememberHome(new java.io.File(System.getProperty("user.dir", ".")).getAbsoluteFile());
            return;
        }        // ③ jar 所在目录 / jar 的上一级
        java.io.File jarDir = updater.cfg.BundledDefaults.jarDir();
        java.io.File[] candidates = {
                jarDir,
                jarDir == null ? null : jarDir.getParentFile()
        };
        for (java.io.File dir : candidates) {
            if (dir != null && new java.io.File(dir, "config.properties").isFile()) {
                applyHome(dir, "jar 位置");
                return;
            }
        }
        // ④ 上次记住的程序文件夹（jar 被挪到别处以后靠这条）
        java.io.File remembered = rememberedHome();
        if (remembered != null && new java.io.File(remembered, "config.properties").isFile()) {
            applyHome(remembered, "上次使用的程序文件夹");
            return;
        }
        String dir = System.getProperty("user.dir", ".");
        String message = "没有找到 config.properties，按当前目录作为程序文件夹继续运行：\n" + dir
                + "\n\n接口地址等常量用内置默认值，python 脚本用 jar 内自带的那份，"
                + "数据目录用「数据文件夹」里的设置。\n"
                + "如果程序装在别处，可以用 --home <程序目录> 指过去。";
        System.out.println(message);
    }

    /** 把某个目录设为程序文件夹，并记下来给下次用 */
    private static void applyHome(java.io.File dir, String why) {
        updater.cfg.ProgramHome.set(dir);
        System.out.println("程序文件夹: " + dir.getAbsolutePath() + "（" + why + "）");
        rememberHome(dir);
    }

    /**
     * 记住程序文件夹，写在 {@code ~/.datatools/updater-home.txt}。
     *
     * <p>为的是「jar 被单独挪走」这一种情况：下次启动既没有 {@code --home}、
     * 当前目录和 jar 附近也都没有 {@code config.properties} 时，还能找回程序文件夹，
     * 除权 / 财报要用的 {@code python/} 脚本和本地数据来源目录就都还在。</p>
     */
    private static void rememberHome(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        String text = dir.getAbsolutePath();
        for (java.io.File file : homePointerFiles()) {
            try {
                if (file.isFile()
                        && text.equals(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim())) {
                    return;
                }
                java.io.File parent = file.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    continue;   // 这个位置写不了，换下一个候选
                }
                java.nio.file.Files.write(file.toPath(),
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            } catch (Exception e) {
                // 记不住不影响本次运行，换下一个候选
            }
        }
    }

    /** 上次记住的程序文件夹；没有记录或读不出来返回 null */
    private static java.io.File rememberedHome() {
        for (java.io.File file : homePointerFiles()) {
            if (file == null || !file.isFile()) {
                continue;
            }
            try {
                String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (text.isEmpty()) {
                    continue;
                }
                java.io.File dir = new java.io.File(text);
                if (dir.isDirectory()) {
                    return dir;
                }
            } catch (Exception ignored) {
                // 读不了就看下一个候选
            }
        }
        return null;
    }

    /**
     * 记录程序文件夹的候选位置：{@code <用户目录>/.datatools/updater-home.txt}，
     * 用户目录不可写时退到环境变量 {@code HOME} / {@code APPDATA} 下。
     *
     * <p>不放在 jar 旁边：这个文件存在的意义正是「jar 已经被挪走了」。
     * 也不碰数据文件夹：启动时它还不知道在哪。</p>
     */
    private static java.util.List<java.io.File> homePointerFiles() {
        java.util.List<java.io.File> files = new java.util.ArrayList<java.io.File>();
        addPointer(files, System.getProperty("user.home", ""), ".datatools");
        addPointer(files, System.getenv("HOME"), ".datatools");
        addPointer(files, System.getenv("APPDATA"), "datatools");
        return files;
    }

    private static void addPointer(java.util.List<java.io.File> files, String base, String folder) {
        if (base == null || base.trim().isEmpty()) {
            return;
        }
        java.io.File file = new java.io.File(new java.io.File(base.trim(), folder), "updater-home.txt");
        if (!files.contains(file)) {
            files.add(file);
        }
    }

    private static void printMenu() {
        System.out.println();
        System.out.println("========== StockDataUpdater 数据更新程序 ==========");
        System.out.println("  数据文件夹: " + UpdaterDataDir.root()
                + "（" + UpdaterDataDir.source().label() + "）");
        System.out.println("  股票库目录: " + UpdaterDataDir.stockDir());
        System.out.println("  1. 盘后数据下载（从本地数据来源）");
        System.out.println("  2. 补最新价格（没有库时自动建库）");
        System.out.println("  3. 除权数据更新");
        System.out.println("  4. 下载财报数据（从本地数据来源）");
        System.out.println("  5. 分钟数据（5分钟线）");
        System.out.println("  6. 更新股票名称（写独立名称库）");
        System.out.println("  7. 补充历史记录（从本地数据来源补全全部历史）");
        System.out.println("  0. 退出");
        System.out.print("请选择: ");
    }

    /** 执行一个任务编号；除权有子菜单 */
    private static void runTask(String choice) {
        try {
            switch (choice) {
                case "1":
                    runClass("updater.main.down.MainDownLoadFromTdx");
                    break;
                case "2":
                    runClass("updater.main.old.search.MainSearchByUpdate");
                    break;
                case "3":
                    runDividendMenu();
                    break;
                case "4":
                    runFinance();
                    break;
                case "5":
                    runClass("updater.fetchdata.tdx.min.MinDataUpdater");
                    break;
                case "6":
                    runClass("updater.mainwave.StockNameUpdater");
                    break;
                case "7":
                    runClass("updater.main.down.MainBackfillHistoryFromTdx");
                    break;
                default:
                    System.out.println("无效的选择: " + choice);
            }
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            System.out.println("任务执行失败: " + cause.getMessage());
            cause.printStackTrace();
        }
    }

    /** 反射调用更新类的 main（和主菜单同一入口） */
    private static void runClass(String className) throws Exception {
        System.out.println(">> 开始执行 " + className);
        Class<?> clazz = Class.forName(className);
        Method main = clazz.getMethod("main", String[].class);
        main.invoke(null, (Object) new String[]{});
        System.out.println(">> 执行完成 " + className);
    }

    /** 除权子菜单：与主菜单第 3 项一致 */
    private static void runDividendMenu() throws Exception {
        System.out.println("除权数据来源:");
        System.out.println("  1. 从本地数据来源加载数据（本地 gbbq 文件）");
        System.out.println("  2. 从大智慧加载数据");
        System.out.print("请选择: ");
        Scanner scanner = new Scanner(System.in);
        String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        switch (line) {
            case "1":
                runClass("updater.main.down.MainDividendFromTdxLocal");
                break;
            case "2":
                runClass("updater.main.down.MainDividendFromDzh");
                break;
            default:
                System.out.println("已取消");
        }
    }

    /** 财报下载：输入季度或回车取最新；与主菜单第 4 项同一套服务 */
    private static void runFinance() throws Exception {
        System.out.print("财报季度(yyyyMM，如 202603；回车=更新最新): ");
        Scanner scanner = new Scanner(System.in);
        String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        updater.fetchdata.tdx.finance.FinanceImportService.PrepareResult prepared;
        if (line.isEmpty()) {
            prepared = updater.fetchdata.tdx.finance.FinanceImportService.prepareLatest();
        } else {
            String normalized = updater.fetchdata.tdx.finance.FinanceImportService.normalizeQuarterInput(line);
            if (normalized == null) {
                System.out.println("季度格式不正确: " + line + "（应为 yyyyMM 如 202603）");
                return;
            }
            prepared = updater.fetchdata.tdx.finance.FinanceImportService.prepareByInput(normalized);
        }

        System.out.println("报告期: " + prepared.reportDate
                + "  服务器文件: " + prepared.sourceFile);
        boolean overwrite = prepared.hasLocalData();
        if (overwrite) {
            System.out.println("本地已有该季度数据 " + prepared.localRowCount
                    + " 条，将覆盖（重跑请输入 n 取消）");
            Scanner scanner2 = new Scanner(System.in);
            String confirm = scanner2.hasNextLine() ? scanner2.nextLine().trim() : "";
            if ("n".equalsIgnoreCase(confirm)) {
                System.out.println("已取消");
                return;
            }
        }
        System.out.println("开始下载并导入...");
        updater.fetchdata.tdx.finance.FinanceImportService.ImportResult result =
                updater.fetchdata.tdx.finance.FinanceImportService.importReport(
                        prepared, overwrite, System.out::println);
        System.out.println("导入完成: 报告期 " + result.reportDate
                + "  记录数 " + result.rows + "  字段数 " + result.columns);
    }
}
