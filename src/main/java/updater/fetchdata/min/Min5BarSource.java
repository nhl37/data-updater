package updater.fetchdata.min;

import updater.fetchdata.tdx.min.TdxMinBar;

import java.util.List;

/**
 * 一条<b>在线</b> 5 分钟取数线路：一只股票一次，返回按时间升序的 5 分钟 K。
 *
 * <p>实现类只管「怎么把这只的 5 分钟拿回来」，不管写库、不管并发、不管失败重试 ——
 * 那些全在 {@link Min5Online} 里统一做，所以几条线路写出来的数据口径必然一致。</p>
 *
 * <p>{@link #id()} 用的是跟 {@code UpdateSources} 一样的稳定标识（小写英文），
 * 台账、界面上看到的都是它，混淆不会影响。</p>
 */
public interface Min5BarSource {

    /** 稳定标识：eastmoney / sina / tencent */
    String id();

    /** 给人看的名字，日志里说「哪家补的」用 */
    String label();

    /**
     * 取一只股票的 5 分钟线，按时间升序。
     *
     * @param code     带市场前缀的代码（sh600000 / sz000001 / bj430047）
     * @param lastTime 本地库里最后一根的 {@code yyyyMMddHHmm}；0 = 库里没有，
     *                 此时尽力往回多拿一些，拿不到更多也不算错
     * @return 这一只取到的 K（可能为空 = 这只确实没有数据）；拿不到要抛异常，
     *         由调用方决定换下一条线路
     */
    List<TdxMinBar> fetch(String code, long lastTime) throws Exception;
}
