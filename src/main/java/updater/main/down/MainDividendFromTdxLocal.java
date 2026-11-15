package updater.main.down;

import updater.fetchdata.DivdendDataWriter;
import updater.fetchdata.DividendData;
import updater.fetchdata.tdx.xdxr.TdxLocalXdxrReader;
import updater.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用 python(pytdx) 读取本地数据来源的 gbbq 除权数据，更新本地各股票 db 中的 T_Divdend 表。
 */
public class MainDividendFromTdxLocal {

    public static void main(String[] args) {
        Log.info("MainDividendFromTdxLocal-start");
        long startTime = System.currentTimeMillis();
        try {
            // 1. python(pytdx) 读取本地 gbbq -> DividendData
            Map<String, List<DividendData>> allDividendMap = TdxLocalXdxrReader.readFromLocalTdx();
            Log.info("除权数据涉及股票数: " + allDividendMap.size());
            if (allDividendMap.isEmpty()) {
                Log.info("MainDividendFromTdxLocal-end(没有可更新的除权数据)");
                return;
            }

            // 2. 写入 T_Divdend（内部会备份旧表并累计前复权因子）
            List<List<DividendData>> allDividendList =
                    new ArrayList<List<DividendData>>(allDividendMap.values());
            DivdendDataWriter.insertForwardAdjusted(allDividendList);

            long cost = (System.currentTimeMillis() - startTime) / 1000L;
            Log.info("MainDividendFromTdxLocal-end 股票数:" + allDividendMap.size()
                    + " 耗时:" + cost + "s");
        } catch (Exception e) {
            Log.error("MainDividendFromTdxLocal 失败: " + e.getMessage());
            // 排查步骤只能打「当前机器上实际在用的路径」。
            // 原来这里写死了开发机的绝对路径，用户看到的是别人的目录，
            // 会以为程序还在读那个位置 —— 真正用的目录反而没说。
            String tdxDir = updater.fetchdata.tdx.TdxDataDir.configuredPath();
            java.io.File script = updater.cfg.ScriptPaths.resolve("python/read_tdx_gbbq.py");
            System.out.println("排查步骤:");
            System.out.println("  1) 当前本地数据来源: "
                    + (tdxDir.isEmpty() ? "（未设置，请在右上「路径设置 → 本地数据来源」里选行情软件安装目录）" : tdxDir));
            System.out.println("  2) 手动执行(带 debug):");
            System.out.println("     python " + (script == null ? "python/read_tdx_gbbq.py" : script.getAbsolutePath())
                    + " --tdx-dir \"" + tdxDir + "\" --output-file data/tdx/tdx_xdxr.csv --debug");
            System.out.println("  3) 查看 csv 是否有数据行: head -5 data/tdx/tdx_xdxr.csv");
            if (!tdxDir.isEmpty()) {
                System.out.println("  4) 看 gbbq 在不在: ls -l \""
                        + new java.io.File(tdxDir, "T0002/hq_cache/gbbq").getAbsolutePath() + "\"");
            }
            e.printStackTrace();
        }
    }
}
