package updater.main.updater;

/**
 * 数据更新程序里的任务。
 *
 * <p>界面左侧按<b>数据类别</b>分成四组（{@link Group}）：股价数据、财报与股东、
 * 研究数据、其他数据。左栏只放「常用入口」；补历史、指数、以及各数据类型的
 * 「查缺补漏」都挪进了对应画面里（日线在「盘后数据下载」、5 分钟在「分钟数据」、
 * 除权在「除权数据更新」），点一下就在那一页开跑，台账照旧。</p>
 *
 * <p>{@code unit} 是台账里每一行的单位（只 / 个季度 / 项），{@code resumable}
 * 表示中断后能不能「接着跑剩下的」—— 能续跑的才需要在台账里记清单。</p>
 *
 * <p>公告同步、股本数据以前分别挂在盘后下载和财报导入后面「顺带跑」，
 * 现在各自是独立任务，想单独补跑不用再等一条长链子走完。</p>
 */
public enum UpdateTask {

    // ---------------------------------------------------------------- 股价数据
    POST_DOWNLOAD("post", "盘后数据下载",
            "从本地数据来源读最近 N 天日线，逐只写入本地库；写完从本地分钟文件补 5 分钟线"
                    + "（不联网）、再同步今日公告",
            "只", true, true, Group.PRICE),

    LATEST_PRICE("price", "补最新价格",
            "从行情接口拉全市场最新价，本地没有库就建；写完会问要不要连 5 分钟线一起补",
            "只", false, true, Group.PRICE),

    // 补历史 / 指数 / 股价查缺补漏都挂在「盘后数据下载」画面里，不再占左栏：
    // 它们跟盘后下载是同一批日线数据的不同入口，分成四个左栏按钮反而让人以为
    // 是四种不同的数据（见 UpdaterFrame 的 POST_DOWNLOAD 参数区）
    HISTORY("history", "补充历史记录",
            "把本地数据来源里的全部历史日线并进本地库，本地没有库就建（场内基金第一次进来靠它）",
            "只", true, true, Group.PRICE, false, false),

    MIN_DATA("min", "分钟数据（5 分钟）",
            "读 5 分钟线入库，只补不删；默认读本地分钟文件，在线这一档可在"
                    + "东方财富 / 新浪 / 腾讯之间逐只换源，三家全取不到时按设置里的"
                    + "「换 IP 命令」重拨一次再试。盘后下载与补最新价格都能顺带补，"
                    + "这里用于单独补跑",
            "项", true, false, Group.PRICE),

    MIN5_INTEGRITY("integrity_min5", "5 分钟 · 查缺补漏",
            "逐只核 T_D_Min5：表在不在、末根有没有落后、最近 10 个交易日有没有整天缺；"
                    + "缺的有本地分钟文件就从文件补，没有的走在线线路",
            "只", true, true, Group.PRICE, false, true),

    DIVIDEND("dividend", "除权数据更新",
            "读取本地除权除息数据，按报告期修正除权表", "项", false, false, Group.PRICE),

    DIVIDEND_INTEGRITY("integrity_dividend", "除权 · 查缺补漏",
            "以本地除权文件为参照逐只核 T_Divdend：缺表 / 整表为空 / 落后于最新除权日的，"
                    + "可以一键整只重写",
            "只", true, true, Group.PRICE, false, true),

    INDEX_DATA("index", "指数数据",
            "取常用指数的全部日线（点数 + 成交量 / 成交额），建在同一个库里；"
                    + "默认读本地日线文件，取不到再走在线；脚本里用 IDX(\"sh000001\", C) 引用，不参与选股",
            "个", false, true, Group.PRICE, false, false),

    INTEGRITY("integrity", "股价数据 · 查缺补漏",
            "逐只对账本地数据来源与本地日线、5 分钟线，找出缺库 / 缺日期 / 分钟线落后，可一键补齐",
            "只", true, true, Group.PRICE, false, true),

    // ---------------------------------------------------------------- 财报与股东
    FINANCE("finance", "下载财报数据",
            "按季度下载入库，导入后自动刷新十大流通股东", "个季度", false, false, Group.FINANCE),

    HOLDER_SYNC("holder_quarters", "同步流通股东",
            "按季度拉全市场十大流通股东，只取流通 A 股", "个季度", false, false, Group.FINANCE),

    SHARE_CAPITAL("capital", "股本数据",
            "从本地财报字段回填各报告期的总股本 / 流通 A 股 / 股东人数；K 线图算换手率要用",
            "项", false, false, Group.FINANCE),

    FINANCE_INTEGRITY("integrity_finance", "财报与股东 · 查缺补漏",
            "按应披露的报告期逐季核财报记录数与十大流通股东缓存；缺哪几只补哪几只",
            "个季度", false, true, Group.FINANCE, true, true),

    // ---------------------------------------------------------------- 研究数据
    RISK_EVENTS("risk_events", "归并风险事件",
            "读本地公告库，把利空公告按类别归并；不联网", "项", false, false, Group.RESEARCH),

    RISK_LIFTS("risk_lifts", "拉取限售解禁",
            "东财解禁表，取前 90 天到未来 12 个月", "项", false, false, Group.RESEARCH),

    RISK_VALUATION("risk_valuation", "计算估值快照",
            "读个股库与财报，重算全市场市值 / PE / PB；不联网", "只", false, false, Group.RESEARCH),

    PIT_NOTICE("pit_notice", "同步财报披露日",
            "东财业绩报表，取最近三年实际披露日；回测按披露日取数", "项", false, false,
            Group.RESEARCH),

    ANNOUNCEMENT("announcement", "公告同步",
            "单独跑一次全市场公告入库（最近 7 天）；盘后下载也会顺带跑，这里用于补跑或回溯更久",
            "项", false, false, Group.RESEARCH),

    // ---------------------------------------------------------------- 其他数据
    STOCK_NAME("name", "更新股票名称",
            "写入独立名称库 stock_name.db", "项", false, false, Group.OTHER),

    NAME_INTEGRITY("integrity_name", "股票名称 · 查缺补漏",
            "按本地股票清单逐只核名称库，缺名称 / 名称为空的可以一键重新获取",
            "只", false, true, Group.OTHER, true, true);

    /** 数据类别：一组一个「查缺补漏」按钮 */
    public enum Group {
        // 股价数据的三个查缺补漏各自挂在对应画面上（日线在「盘后数据下载」、
        // 5 分钟在「分钟数据」、除权在「除权数据更新」），组标题上不再重复放一个
        PRICE("股价数据", "日线 · 分钟 · 除权", null),
        FINANCE("财报与股东", "财报季度 · 十大流通股东 · 股本", "integrity_finance"),
        RESEARCH("研究数据", "风险事件 · 解禁 · 估值 · 披露日 · 公告", null),
        OTHER("其他数据", "股票名称", "integrity_name");

        public final String title;
        public final String caption;
        /** 用 id 而不是枚举常量：静态初始化时本组常量还没建好；null = 本组没有查缺补漏 */
        private final String checkId;

        Group(String title, String caption, String checkId) {
            this.title = title;
            this.caption = caption;
            this.checkId = checkId;
        }

        /** 本组的查缺补漏任务；没有就返回 null，界面上不显示那个按钮 */
        public UpdateTask checkTask() {
            return byId(checkId);
        }
    }

    /**
     * 台账里用的稳定标识。
     *
     * <p>刻意写成字面量而不是 {@code name()}：发布版要过混淆，
     * 枚举常量名一旦被改，老台账里的任务就对不上了（续跑会失效）。</p>
     */
    public final String id;

    public final String title;
    public final String subtitle;
    /** 台账每行的单位 */
    public final String unit;
    /** 是否必须配好本地数据来源目录才能跑 */
    public final boolean needsTdx;
    /** 中断后能否「接着跑剩下的」 */
    public final boolean resumable;
    /** 属于哪一类数据 */
    public final Group group;
    /** 是否出现在左栏的任务按钮列表里（现在全部为 true） */
    public final boolean navVisible;
    /** 是不是某一类数据的「查缺补漏」（决定「重试失败」按钮叫不叫「修复缺失」） */
    public final boolean check;

    UpdateTask(String id, String title, String subtitle, String unit,
               boolean needsTdx, boolean resumable, Group group) {
        this(id, title, subtitle, unit, needsTdx, resumable, group, true, false);
    }

    UpdateTask(String id, String title, String subtitle, String unit,
               boolean needsTdx, boolean resumable, Group group,
               boolean navVisible, boolean check) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.unit = unit;
        this.needsTdx = needsTdx;
        this.resumable = resumable;
        this.group = group;
        this.navVisible = navVisible;
        this.check = check;
    }

    /** 是不是某一类数据的「查缺补漏」 */
    public boolean isCheck() {
        return check;
    }

    /** 按 id 找回任务，找不到返回 null（老台账里可能有已删除的任务） */
    public static UpdateTask byId(String id) {
        if (id == null) {
            return null;
        }
        for (UpdateTask task : values()) {
            if (task.id.equals(id.trim())) {
                return task;
            }
        }
        return null;
    }
}
