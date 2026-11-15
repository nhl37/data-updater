package updater.db;

/**
 * 内置查询 SQL：查询口径随程序一起编译，<b>不再从磁盘 {@code sql/*.sql} 读取</b>。
 *
 * <p>为什么改成常量：安装包只随包分发 {@code config/script/doc/rag/python/easyquotation}，
 * 不含 {@code sql/}。原来按相对路径读 {@code sql/table_day.sql}，装到 Windows / macOS 上
 * 直接「找不到文件」，K 线、回测、脚本整条链路都不工作。写进类里之后，在任何工作目录
 * 都能跑，安装目录也不会再留一堆可被随意改动、改了又不生效的 {@code .sql}。</p>
 *
 * <p>改这里要连带验证：全部是 SQLite 方言（窗口函数、{@code printf}、{@code date(...,'weekday 0')}），
 * 改完至少跑一遍日/周/月/季 K 线与 {@code FIND30MIN} 脚本。源码里的 {@code sql/*.sql}
 * 只是历史参考副本，<b>程序不再读取</b>。</p>
 *
 * <p>文本块需要 Java 17（pom 的 compiler release 已是 17）。</p>
 */
public final class SqlText {

    private SqlText() {
    }

    /** 日线：按累计除权因子前复权，`?` = 截止交易日（含）。 */
    public static final String DAY = """
             WITH day_factor AS (
                SELECT      time, open,close,high,low,vol,amo,
                COALESCE(   (SELECT factor FROM T_Divdend t2  WHERE t2.time > t1.time ORDER BY t2.time asc LIMIT 1)  ,1) AS factor
                FROM t_d_day t1
                WHERE time<=?
                order by time desc--股价倒序 除权正序
              ),

                t_day AS (
             SELECT         time,
                open*factor as open,
                close*factor as close,
                high*factor as high,
                low*factor as low,
                vol,
                amo,
                ROW_NUMBER() OVER ( ORDER BY time) AS row_num
             FROM day_factor
                )
            """;

    /** 周线：按 `date(...,'weekday 0')` 分组（周末为组界），价格前复权。 */
    public static final String WEEK = """
            WITH divdend_price AS (
                SELECT
                    *,
            -- 将整型日期转换为标准日期格式
                   date((substr(printf('%08d', time), 1, 4) || '-' ||
                   substr(printf('%08d', time), 5, 2) || '-' ||
                   substr(printf('%08d', time), 7, 2)) , 'weekday 0') as groupdate,
                   COALESCE((SELECT d.factor
                             FROM t_divdend d
                             WHERE d.time > t1.time
                             ORDER BY d.time asc
                           ),1) AS factor
                FROM t_d_day t1
                WHERE time<=?
                order by time desc--股价倒序 除权正序
                ),
            -- 取得最高价 和 最低价

                t_hl as (select groupdate, max(high*factor) as high,min(low*factor) as low, sum(vol) as vol, sum(amo) as amo
            from divdend_price t1
            group by groupdate
            order by groupdate desc),

            -- 取得开盘价
                t_o as (
            SELECT
                groupdate,
                open*factor as open
            FROM  divdend_price

            group by groupdate
            having time=min(time)
            ORDER BY groupdate desc

                ),
            -- 取得收盘价

                t_c as (
            SELECT
                groupdate,time,
                close*factor as close
            FROM
                divdend_price
            group by groupdate
            having time=max(time)
            ORDER BY groupdate desc

                ),
            -- 生成数据
                t_week AS (select t_c.time, t_hl.high, t_hl.low, t_hl.vol,t_hl.amo,t_o.OPEN ,t_c.close,
                ROW_NUMBER() OVER ( ORDER BY time) AS row_num
            from t_hl,t_o,t_c
            where t_hl.groupdate = t_o.groupdate and t_c.groupdate = t_hl.groupdate)
            """;

    /** 月线：按 `time/100` 分组，价格前复权。 */
    public static final String MONTH = """
            with divdend_price as (
               SELECT
                    *,
                    time/100 AS groupdate,
            -- 使用累计复权因子计算复权后价格
                    COALESCE((SELECT d.factor
                              FROM t_divdend d
                              WHERE  d.time > t1.time
                              ORDER BY d.time asc
                             ),1) AS factor
                    FROM t_d_day t1
                    WHERE time<=?
                    order by time desc--股价倒序 除权正序
            ),
            -- 取得最高价 和 最低价
                t_hl as (select groupdate, max(high*factor) as high,min(low*factor) as low, sum(vol) as vol, sum(amo) as amo
            from divdend_price t1
            group by groupdate
            order by groupdate desc),

            -- 取得开盘价
                t_o as (
            SELECT
                groupdate,
                open*factor as open
            FROM  divdend_price

            group by groupdate
            having time=min(time)
            ORDER BY groupdate desc

                ),
            -- 取得收盘价
                t_c as (
            SELECT
                groupdate,time,
                close*factor as close
            FROM
                divdend_price
            group by groupdate
            having time=max(time)
            ORDER BY groupdate desc

                ),
            -- 生成数据
             t_month AS ( select t_c.time, t_hl.high, t_hl.low, t_hl.vol,t_hl.amo,t_o.OPEN ,t_c.close,
                ROW_NUMBER() OVER ( ORDER BY time) AS row_num
            from t_hl,t_o,t_c
            where t_hl.groupdate = t_o.groupdate and t_c.groupdate = t_hl.groupdate)
            """;

    /** 季线：按「年*10 + 季度」分组，价格前复权。 */
    public static final String QUARTER = """
            with divdend_price as (
               SELECT
                    *,
                    time/10000*10 + ((time/100%100-1)/3+1) AS groupdate,
            -- 使用累计复权因子计算复权后价格
                    COALESCE((SELECT d.factor
                              FROM t_divdend d
                              WHERE  d.time > t1.time
                              ORDER BY d.time asc
                             ),1) AS factor
                    FROM t_d_day t1
                    WHERE time<=?
                    order by time desc--股价倒序 除权正序
            ),
            -- 取得最高价 和 最低价
                t_hl as (select groupdate, max(high*factor) as high,min(low*factor) as low, sum(vol) as vol, sum(amo) as amo
            from divdend_price t1
            group by groupdate
            order by groupdate desc),

            -- 取得开盘价
                t_o as (
            SELECT
                groupdate,
                open*factor as open
            FROM  divdend_price

            group by groupdate
            having time=min(time)
            ORDER BY groupdate desc

                ),
            -- 取得收盘价
                t_c as (
            SELECT
                groupdate,time,
                close*factor as close
            FROM
                divdend_price
            group by groupdate
            having time=max(time)
            ORDER BY groupdate desc

                ),
            -- 生成数据
             t_quarter AS ( select t_c.time, t_hl.high, t_hl.low, t_hl.vol,t_hl.amo,t_o.OPEN ,t_c.close,
                ROW_NUMBER() OVER ( ORDER BY time) AS row_num
            from t_hl,t_o,t_c
            where t_hl.groupdate = t_o.groupdate and t_c.groupdate = t_hl.groupdate)
            """;


    /**
     * 按周期取对应的前缀 SQL（与 {@link DBConnection#CYCLE_DAY} 等常量对齐）。
     *
     * @param cycle K 线周期
     * @return 只有 {@code WITH ...} 前缀，后面由调用方拼列与 {@code from}
     */
    public static String forCycle(int cycle) {
        switch (cycle) {
            case DBConnection.CYCLE_DAY:
                return DAY;
            case DBConnection.CYCLE_WEEK:
                return WEEK;
            case DBConnection.CYCLE_MONTH:
                return MONTH;
            case DBConnection.CYCLE_QUARTER:
                return QUARTER;
            default:
                throw new IllegalArgumentException("不支持的 K 线周期: " + cycle);
        }
    }
}
