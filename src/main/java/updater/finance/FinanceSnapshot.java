package updater.finance;

import updater.db.DBException;
import updater.pit.PitStore;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本用的财报快照：按股票缓存最近若干报告期，再按交易日取「当时已经披露的最近一期」。
 *
 * <p>脚本在扫描全市场时，一只股票会问几百次「这一天的财报是什么」。
 * 每次都去查 SQLite 会慢得没法用，所以每只股票只读一次时间线，之后在内存里按日期找。
 * 缓存按最久未使用淘汰，扫描几千只股票也只占几十 MB。</p>
 *
 * <p><b>披露日在装载时就算好</b>（{@link Timeline#visibleDates}）：以前每取一期都要
 * 问一次 {@link PitStore#noticeDate}，而它内部是 <b>synchronized + 线性扫</b>，
 * 一只股票几百根K线乘上几个指标就是几十万次上锁。现在一只股票只解析 24 次，
 * 之后全是数组比较。代价是 pit.db 更新后要能失效，所以 {@link #dbStamp()}
 * 把 pit.db 的修改时间也算进去了。</p>
 *
 * <p>财报库或披露日库更新后（文件修改时间变化）自动失效，不用重启程序。</p>
 */
public final class FinanceSnapshot {

    /** 缓存多少只股票的时间线；一只 24 期，约 40KB，256 只约 10MB */
    private static final int MAX_CODES = 256;
    private static final long STAMP_CHECK_INTERVAL = 5000L;

    private static final Map<String, Timeline> CACHE =
            new LinkedHashMap<String, Timeline>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Timeline> eldest) {
                    return size() > MAX_CODES;
                }
            };

    private static long cacheStamp = -1;
    private static long stampCheckedAt;

    private FinanceSnapshot() {
    }

    /**
     * 一只股票的财报时间线（报告期倒序），带两处预计算：每期的「可见日」和「报告期 → 下标」。
     *
     * <p>两处都是为了让热路径只剩数组比较：脚本每根K线、每个指标都要问一次
     * 「这天能看到哪一期」，这条路径上不能再有上锁和查库。</p>
     */
    public static final class Timeline {

        private final List<FinanceRecord> rows;
        /** 报告期 yyyyMMdd，倒序，与 rows 同序 */
        private final int[] reportDates;
        /** 每期最早能被看到的日子（披露日优先，没有披露日就是报告期本身） */
        private final int[] visibleDates;
        private final Map<Integer, Integer> indexByReportDate;

        private Timeline(List<FinanceRecord> rows) {
            this.rows = rows;
            int count = rows.size();
            this.reportDates = new int[count];
            this.visibleDates = new int[count];
            this.indexByReportDate = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                FinanceRecord row = rows.get(i);
                int reportDate = parseDate(row.getReportDate());
                reportDates[i] = reportDate;
                visibleDates[i] = reportDate > 0 ? FinanceSnapshot.visibleDate(row.getCode(), reportDate) : 0;
                if (reportDate > 0) {
                    indexByReportDate.put(reportDate, i);
                }
            }
        }

        public int size() {
            return rows.size();
        }

        public FinanceRecord row(int index) {
            return index >= 0 && index < rows.size() ? rows.get(index) : null;
        }

        public List<FinanceRecord> rows() {
            return rows;
        }

        public int reportDate(int index) {
            return index >= 0 && index < reportDates.length ? reportDates[index] : 0;
        }

        public int visibleDate(int index) {
            return index >= 0 && index < visibleDates.length ? visibleDates[index] : 0;
        }

        /**
         * 某个报告期在时间线里的下标；没有这一期返回 -1。
         *
         * <p>算 TTM 要「上年同期」和「上年年报」两期，有了这张表就是一次哈希，
         * 不用每次遍历 24 行。</p>
         */
        public int indexOfReport(int reportDate) {
            Integer index = indexByReportDate.get(reportDate);
            return index == null ? -1 : index;
        }

        /**
         * 某个交易日当时<b>已经能看到</b>的最近一期下标；没有返回 -1。
         *
         * <p>「能看到」= 披露日 ≤ 当天。数组按报告期倒序，所以从新往旧找第一个满足的即可。
         * 理论上披露日也随报告期递增，但补录、更正公告会让它偶尔不单调，因此这里
         * 不假设有序，老老实实线性扫（24 次 int 比较，比一次哈希还便宜）。</p>
         */
        public int latestIndexAsOf(int date) {
            if (date <= 0) {
                return -1;
            }
            for (int i = 0; i < rows.size(); i++) {
                int visible = visibleDates[i];
                if (visible > 0 && visible <= date) {
                    return i;
                }
            }
            return -1;
        }

        /** 某个交易日当时能看到、且带某个字段的最近一期下标；没有返回 -1 */
        public int latestIndexWith(int date, int field) {
            if (date <= 0) {
                return -1;
            }
            for (int i = 0; i < rows.size(); i++) {
                int visible = visibleDates[i];
                if (visible > 0 && visible <= date && rows.get(i).has(field)) {
                    return i;
                }
            }
            return -1;
        }

        /** 下标 index 之后再往前（更早）一期、带某个字段的记录下标；没有返回 -1 */
        public int previousIndexWith(int index, int field) {
            if (index < 0) {
                return -1;
            }
            for (int i = index + 1; i < rows.size(); i++) {
                if (rows.get(i).has(field)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * 某个交易日当时<b>已经披露</b>的最近一期财报；没有返回 null。
     *
     * <p>比较的是「实际披露日」不是「报告期」：三季报的报告期是 9/30，但多数公司 10 月下旬才公布，
     * 按报告期取数等于提前三周知道结果。披露日来自 {@link PitStore}（东财 NOTICE_DATE）；
     * 没有这条记录时退回报告期比较，与旧版行为一致 —— 不知道就照老规矩办，别让缺数据把回测结果算崩。</p>
     */
    public static FinanceRecord latestAsOf(String stockCode, int date) {
        Timeline line = timeline(stockCode);
        return line.row(line.latestIndexAsOf(date));
    }

    /** 某一期财报最早什么时候能被看到：有披露日用披露日，没有就用报告期 */
    public static int visibleDate(String stockCode, int reportDate) {
        int notice = PitStore.noticeDate(stockCode, reportDate);
        return notice > 0 ? notice : reportDate;
    }

    /**
     * 用给定的一组财报记录造一条时间线（{@code rows} 必须按报告期倒序）。
     *
     * <p>给测试和离线工具用：真实的 {@link #timeline(String)} 要读库，算指标的逻辑
     * 却只关心「哪一期、什么时候能看到」，不必为了验算 TTM 去造一个财报库。</p>
     */
    public static Timeline of(List<FinanceRecord> rows) {
        return new Timeline(rows == null ? Collections.<FinanceRecord>emptyList() : rows);
    }

    /**
     * 最近的财报时间线（按报告期倒序）。读不到时返回空表。
     */
    public static synchronized Timeline timeline(String stockCode) {
        String code = normalize(stockCode);
        if (code.isEmpty()) {
            return new Timeline(Collections.<FinanceRecord>emptyList());
        }
        long now = System.currentTimeMillis();
        if (now - stampCheckedAt >= STAMP_CHECK_INTERVAL) {
            stampCheckedAt = now;
            long stamp = dbStamp();
            if (stamp != cacheStamp) {
                CACHE.clear();
                cacheStamp = stamp;
            }
        }
        Timeline cached = CACHE.get(code);
        if (cached != null) {
            return cached;
        }
        Timeline loaded = new Timeline(load(code));
        CACHE.put(code, loaded);
        return loaded;
    }

    /**
     * 最近的财报时间线（按报告期倒序）。读不到时返回空表。
     */
    public static List<FinanceRecord> records(String stockCode) {
        return timeline(stockCode).rows();
    }

    private static List<FinanceRecord> load(String code) {
        try {
            return FinanceRepository.loadRecords(code, FinanceRepository.DEFAULT_LIMIT);
        } catch (DBException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /**
     * 财报库<b>或披露日库</b>的修改时间。
     *
     * <p>两个库是分开同步的（财报走 gpcw/东财业绩报表，披露日走 {@code PitSync}）。
     * 只盯财报库的话，刚补完披露日、财报库没动，缓存里的可见日就还是旧的，
     * 回测会继续按报告期提前看到财报。</p>
     */
    private static long dbStamp() {
        long stamp = fileStamp(FinanceRepository.dbFilePath());
        long pit = fileStamp(PitStore.dbFilePath());
        return stamp ^ (pit * 31L);
    }

    private static long fileStamp(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return 0L;
            }
            File file = new File(path);
            return file.isFile() ? file.lastModified() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toLowerCase();
    }

    /** yyyyMMdd → int；格式不对返回 0 */
    public static int parseDate(String text) {
        if (text == null || text.length() < 8) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(0, 8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 取时间线里某条记录之前、带某个字段的上一条；没有返回 null */
    public static FinanceRecord previousWith(List<FinanceRecord> rows, int index, int field) {
        if (rows == null || index < 0) {
            return null;
        }
        for (int i = index + 1; i < rows.size(); i++) {
            if (rows.get(i).has(field)) {
                return rows.get(i);
            }
        }
        return null;
    }

    /** 时间线里最后一条带某字段、且当时已披露的记录；没有返回 null */
    public static FinanceRecord latestWith(List<FinanceRecord> rows, int date, int field) {
        if (rows == null) {
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            FinanceRecord row = rows.get(i);
            int reportDate = parseDate(row.getReportDate());
            if (reportDate > 0 && row.has(field)
                    && visibleDate(row.getCode(), reportDate) <= date) {
                return row;
            }
        }
        return null;
    }

    /** 给测试/调试用：清掉缓存 */
    public static synchronized void invalidate() {
        CACHE.clear();
        cacheStamp = -1;
        stampCheckedAt = 0;
    }
}
