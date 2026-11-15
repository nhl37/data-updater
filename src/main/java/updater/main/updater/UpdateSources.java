package updater.main.updater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 更新任务的线路表：每个任务一条默认线路，后面按顺序列几条备用线路。
 *
 * <p><b>线路在画面上全部列出来</b>：每个任务的参数区都会显示这张表的中文名与
 * 说明，默认是 {@link #AUTO}（自动按第一条起步），用户也可以直接点选某一条。
 * 默认线路读取失败、自动重试后仍连不上时，由 {@code UpdaterFrame} 问一句
 * 要不要换线路，确认后才依次试备用线路。</p>
 *
 * <p><b>切换绝不保存</b>：自动切换只存在于那一次执行的内存参数副本里，
 * 台账、config.properties、任何偏好文件都不写；用户手动选的那条也只对本次执行生效，
 * 切任务、重开程序、续跑、重试失败都重新回到默认线路。原因是备用线路的速度、
 * 字段完整度跟默认线路不一样，悄悄记住它会让用户下次莫名其妙变慢或数据变薄。</p>
 *
 * <p>列表里<b>第一个就是默认线路</b>。{@link #AUTO}（空串）表示「不指定」，
 * 由 {@link #resolve} 折成第一个。</p>
 */
public final class UpdateSources {

    /** 不指定线路：折成任务的默认线路 */
    public static final String AUTO = "";

    public static final String TDX = "tdx";
    public static final String TENCENT = "tencent";
    public static final String SINA = "sina";
    public static final String EASTMONEY = "eastmoney";
    public static final String DZH = "dzh";
    /** 在线逐只取、多家依次兜底（目前只有 5 分钟线这一档用得到） */
    public static final String ONLINE = "online";

    /** 画面上「自动」那一档的文字 */
    public static final String AUTO_LABEL = "默认线路（自动）";
    public static final String AUTO_NOTE = "按下面第一条起步；连不上时再依次试备用线路";

    /** 线路给人看的名字。id 仍只用于内部分流，混淆不影响。 */
    public static String labelOf(String id) {
        if (id == null) {
            return AUTO_LABEL;
        }
        switch (id.trim().toLowerCase(Locale.ROOT)) {
            case TDX:
                return "本地行情文件";
            case TENCENT:
                return "腾讯行情";
            case SINA:
                return "新浪行情";
            case EASTMONEY:
                return "东方财富";
            case ONLINE:
                return "在线行情（多家依次）";
            case DZH:
                return "大智慧除权文件";
            default:
                return AUTO_LABEL;
        }
    }

    /** 这一条线路取的是什么、有什么取舍，直接写在界面上 */
    public static String noteOf(String id) {
        if (id == null) {
            return AUTO_NOTE;
        }
        switch (id.trim().toLowerCase(Locale.ROOT)) {
            case TDX:
                return "本地数据来源目录里的日线 / 分钟 / 除权文件，一次读全市场，最快";
            case TENCENT:
                return "在线批量取最新价与名称；5 分钟线逐只取，只给成交量（成交额按均价折算）";
            case SINA:
                return "在线备用，取最新价、名称与 5 分钟线；5 分钟一次最多约 20 个交易日";
            case EASTMONEY:
                return "在线逐只取日线与 5 分钟线，5 分钟能回溯的窗口最深（约 60 个交易日）";
            case ONLINE:
                return "东方财富 → 新浪 → 腾讯，逐只取不到就自动换下一家；只补不删";
            case DZH:
                return "另一套本地除权除息文件";
            default:
                return AUTO_NOTE;
        }
    }

    public static String labelOf(Source source) {
        return source == null ? AUTO_LABEL : labelOf(source.id);
    }

    public static String noteOf(Source source) {
        return source == null ? AUTO_NOTE : noteOf(source.id);
    }

    /** 一条线路。id 只用于内部分流，不出现在界面上。 */
    public static final class Source {
        /** 稳定标识（台账/参数里传它，混淆也不受影响） */
        public final String id;
        /** 跑这条线路要不要先配好本地数据来源目录 */
        public final boolean needsTdx;

        Source(String id, boolean needsTdx) {
            this.id = id;
            this.needsTdx = needsTdx;
        }
    }

    private static final Map<UpdateTask, List<Source>> TABLE =
            new LinkedHashMap<UpdateTask, List<Source>>();

    private static void put(UpdateTask task, Source... sources) {
        List<Source> list = new ArrayList<Source>(sources.length);
        Collections.addAll(list, sources);
        TABLE.put(task, Collections.unmodifiableList(list));
    }

    static {
        // 盘后数据下载：默认读本地日线文件；备用逐只请求在线日线
        put(UpdateTask.POST_DOWNLOAD,
                new Source(TDX, true),
                new Source(EASTMONEY, false));

        // 最新价：默认一批 60 只，两条备用依次兜底
        put(UpdateTask.LATEST_PRICE,
                new Source(TENCENT, false),
                new Source(SINA, false),
                new Source(EASTMONEY, false));

        // 补充历史：默认读本地全部历史日线，备用在线逐只拉
        put(UpdateTask.HISTORY,
                new Source(TDX, true),
                new Source(EASTMONEY, false));

        // 除权：默认读本地数据来源的除权文件，备用走另一套本地除权文件（大智慧）。
        // 在线抓除权（东财）那条线路已经下线：它的字段口径与本地文件不一致，
        // 只取了「除权日 + 方案原文」，修正效果不如直接读两套本地文件。
        put(UpdateTask.DIVIDEND,
                new Source(TDX, true),
                new Source(DZH, true));

        // 股票名称：名称跟着行情返回一起取，跟最新价同一套线路
        put(UpdateTask.STOCK_NAME,
                new Source(TENCENT, false),
                new Source(SINA, false),
                new Source(EASTMONEY, false));

        // 5 分钟线：默认读本地数据来源的分钟文件（一次读全市场），读不出来再走在线逐只补。
        // 在线那一档由三家组成：「在线行情（多家依次）」是东财 → 新浪 → 腾讯逐只兜底，
        // 后面三条是点名只要某一家（用户想锁定、或者某一家临时抽风时换过去）。
        // 盘后下载顺带补 5 分钟线时只走第一行（本地文件、不联网），见 UpdaterFrame。
        put(UpdateTask.MIN_DATA,
                new Source(TDX, true),
                new Source(ONLINE, false),
                new Source(EASTMONEY, false),
                new Source(SINA, false),
                new Source(TENCENT, false));
    }

    private UpdateSources() {
    }

    /** 这个任务的全部线路（第一个是默认线路） */
    public static List<Source> of(UpdateTask task) {
        List<Source> list = TABLE.get(task);
        return list == null ? Collections.<Source>emptyList() : list;
    }

    /** 默认线路；没有配置线路的任务返回 null */
    public static Source primary(UpdateTask task) {
        List<Source> list = of(task);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 默认线路之后的备用线路，按尝试顺序返回。
     *
     * <p>这些线路也一并显示在界面上，用户可以直接点选；自动切换则由失败后的
     * 重试逻辑在内存里依次试用。</p>
     */
    public static List<Source> backups(UpdateTask task) {
        List<Source> list = of(task);
        return list.size() < 2 ? Collections.<Source>emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<Source>(list.subList(1, list.size())));
    }

    /**
     * 把参数里的线路折成真实线路：空串 / 不认识的值都折回默认线路。
     *
     * <p>折回而不是报错，是因为参数可能来自旧台账（那时候还没有线路这一项）。</p>
     */
    public static Source resolve(UpdateTask task, String id) {
        List<Source> list = of(task);
        if (list.isEmpty()) {
            return null;
        }
        if (id == null || id.trim().isEmpty()) {
            return list.get(0);
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Source source : list) {
            if (source.id.equals(wanted)) {
                return source;
            }
        }
        return list.get(0);
    }

    /** 折成线路 id（永远非空；没有配置线路的任务返回 {@link #AUTO}） */
    public static String resolveId(UpdateTask task, String id) {
        Source source = resolve(task, id);
        return source == null ? AUTO : source.id;
    }

    /**
     * 这次执行要不要先配好本地数据来源目录。
     *
     * <p>没有配置线路的任务（体检、分钟线、财报）沿用 {@link UpdateTask#needsTdx}。</p>
     */
    public static boolean needsTdx(UpdateTask task, String id) {
        Source source = resolve(task, id);
        return source == null ? task.needsTdx : source.needsTdx;
    }
}
