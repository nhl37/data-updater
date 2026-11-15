package updater.fetchdata;

import updater.fetchdata.tdx.TdxDataDir;
import updater.fetchdata.tdx.kdata.ReaderFromTdx;
import updater.log.Log;
import updater.mainwave.dao.StockInfoDAO;
import updater.name.StockNameStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 股票代码清单的来源。
 *
 * <p>「补最新价格」要按代码逐只请求行情，所以必须先知道有哪些股票。
 * 正常情况代码就来自股票数据目录里的 .db 文件名；但<b>全新安装时一个 .db 都没有</b>，
 * 这时得有两个兜底来源，否则功能一上来就报「目录里没有 .db 文件」：</p>
 * <ol>
 *   <li>已挂载的股票名称库（stock_info 表里全是代码）</li>
 *   <li>本地数据来源的 vipdoc/{sh,sz}/lday 的 .day 文件名（用户既然有本地数据来源，代码清单就在那儿）</li>
 * </ol>
 *
 * <p>代码统一规范成「市场前缀 + 6 位」（sh600000 / sz000830），因为本地库文件名就是这个格式，
 * 建库时必须对得上。</p>
 */
public final class StockCodeSource {

    /** 代码清单的来源，决定「这一次是不是在新建数据库」 */
    public enum Kind {
        /** 目录里已有个股 .db —— 正常增量更新 */
        DB_FILES("本地股票库文件"),
        /** 目录里还没有库，代码取自名称库 —— 会新建数据库 */
        NAME_DB("股票名称库"),
        /** 目录里还没有库，代码取自本地数据来源的 vipdoc —— 会新建数据库 */
        TDX_VIPDOC("本地日线文件");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 解析结果 */
    public static final class Result {
        public final Kind kind;
        public final List<String> codes;
        /** 来源的落点（目录或文件），用于界面提示 */
        public final String detail;

        Result(Kind kind, List<String> codes, String detail) {
            this.kind = kind;
            this.codes = codes == null ? Collections.<String>emptyList() : codes;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isEmpty() {
            return codes.isEmpty();
        }

        /** true = 本次会把库从无到有建出来 */
        public boolean createsNewLibrary() {
            return kind != null && kind != Kind.DB_FILES;
        }

        public String describe() {
            if (kind == null) {
                return "（没有可用的代码来源）";
            }
            return kind.label() + ": " + detail;
        }
    }

    private StockCodeSource() {
    }

    /**
     * 按「本地库 → 名称库 → 本地数据来源」的顺序找代码清单。
     *
     * @param stockDir 挂载的股票数据目录（里面可能一个 .db 都没有）
     */
    public static Result resolve(File stockDir) {
        List<String> fromDb = RealtimeQuoteFetcher.listStockCodes(stockDir);
        if (!fromDb.isEmpty()) {
            return new Result(Kind.DB_FILES, fromDb,
                    stockDir == null ? "" : stockDir.getAbsolutePath());
        }

        List<String> fromNameDb = nameDbCodes();
        if (!fromNameDb.isEmpty()) {
            File nameDb = StockNameStore.dbFile();
            return new Result(Kind.NAME_DB, fromNameDb,
                    nameDb == null ? "" : nameDb.getAbsolutePath());
        }

        List<String> fromTdx = tdxCodes();
        if (!fromTdx.isEmpty()) {
            return new Result(Kind.TDX_VIPDOC, fromTdx,
                    TdxDataDir.configuredPath() + "vipdoc");
        }

        return new Result(null, Collections.<String>emptyList(), "");
    }

    /** 挂载的股票名称库里的代码；没挂载 / 读失败返回空表（不抛异常，交给上层给提示） */
    private static List<String> nameDbCodes() {
        File nameDb = StockNameStore.dbFile();
        if (nameDb == null || !nameDb.isFile()) {
            return Collections.emptyList();
        }
        try {
            List<String> codes = new StockInfoDAO().getStockCodes();
            return normalize(codes);
        } catch (Exception e) {
            Log.out("读取名称库代码清单失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 本地数据来源的 vipdoc 里的个股日线文件名；没配本地数据来源目录 / 目录不在返回空表 */
    private static List<String> tdxCodes() {
        String tdxPath = TdxDataDir.configuredPath();
        if (tdxPath.isEmpty()) {
            return Collections.emptyList();
        }
        File vipdoc = new File(tdxPath, "vipdoc");
        if (!vipdoc.isDirectory()) {
            return Collections.emptyList();
        }
        List<String> codes = new ArrayList<>();
        for (String prefix : new String[]{"sh", "sz"}) {
            File dir = new File(new File(vipdoc, prefix), "lday");
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String code = ReaderFromTdx.codeOf(prefix, file.getName().toLowerCase(Locale.ROOT));
                if (code != null) {
                    codes.add(code);
                }
            }
        }
        // 目录列举顺序是文件系统的顺序，排一下：日志和建库顺序都可预期
        Collections.sort(codes);
        return normalize(codes);
    }

    /** 规范成 sh600000 这种市场前缀写法，并按出现顺序去重 */
    private static List<String> normalize(List<String> codes) {
        Set<String> unique = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                if (code == null || code.trim().isEmpty()) {
                    continue;
                }
                unique.add(RealtimeQuoteFetcher.toMarketCode(code));
            }
        }
        return new ArrayList<>(unique);
    }
}
