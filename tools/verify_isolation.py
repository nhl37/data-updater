#!/usr/bin/env python3
"""发布物隔离断言：两个 jar 互不包含对方代码、图标、样式，抓取能力只在更新器里。

用法：
  verify_isolation.py --updater StockDataUpdater.jar                 # 单包自检
  verify_isolation.py --main StockSearch.jar --updater StockDataUpdater.jar  # 全量对比
"""
import argparse
import sys
import zipfile

# 只出现在「数据抓取/下载/同步」实现里的特征；主程序 jar 中一个都不许有。
# 刻意不放 vipdoc / gpcw / akshare / pytdx 这类「数据源名称」：主程序只读本地库，
# 界面说明里提到这些文件格式是正常的数据口径描述，不是抓取代码。
FETCH_MARKERS = [
    b'datacenter-web.eastmoney.com',
    b'RPT_DMSK_HOLDERS',
    b'np-anotice-stock.eastmoney.com',
    b'emweb.securities.eastmoney.com',
    b'MainDownLoadFromTdx',
    b'MainBackfillHistoryFromTdx',
    b'FinanceImportService',
    b'FinancePythonRunner',
    b'HolderSync',
    b'EastMoneyDayFetcher',
    b'RealtimeQuoteFetcher',
    b'LatestPriceUpdater',
    b'MainDividendFromDzh',
    b'MinDataUpdater',
    b'StockNameUpdater',
    b'UpdaterMain',
    b'UpdateLedger',
    b'DataIntegrityChecker',
]

# 主程序专属类/样式（更新器 jar 里不许出现）
MAIN_MARKERS = [
    b'business/main/MainFrame',
    b'business/main/Launcher',
    b'business/backtest/BacktestFrame',
    b'business/factor/FactorFrame',
    b'business/finance/FinanceAiLauncher',
    b'business/ui/AppTheme',
    b'business/ui/UiBootstrap',
    b'business/ui/AppIcon',
]

# 更新器专属命名空间（主程序 jar 里不许出现）
UPDATER_PREFIXES = ('updater/', 'uicons/', 'u/')

# 身份指纹：这几个串只要出现在更新器里，就等于自报家门。
# 混淆改名挡不住它们 —— 字符串常量原样躺在常量池里，strings 或反编译一眼可见。
OWN_FINGERPRINTS = [
    b'StockSearch',          # 对方产品名（旧名，仍要挡住老包）
    '深玖量化'.encode('utf-8'),   # 对方产品名（现名）
    b'stocksearch',          # 对方的系统属性/目录名
    b'.stocksearch',         # 对方的用户目录
    b'deep9999',             # 作者署名
    b'license.key',          # 对方的授权文件
    b'vault.key',            # 对方的密钥文件
    b'LocalVault',           # 对方的密钥派生常量
    b'Sealed.v1',
    b'Portable.v1',
    '主程序'.encode('utf-8'),        # 界面文案里点出对方存在
    '数据插件'.encode('utf-8'),      # 对方的功能模块名
    '授权'.encode('utf-8'),          # 授权体系
    '机器码'.encode('utf-8'),
    '推荐码'.encode('utf-8'),
    '会员'.encode('utf-8'),          # 账户/付费体系
    '套餐'.encode('utf-8'),
    b'/api/auth',             # 对方的服务端接口
    b'/api/membership',
    b'/api/orders',
    b'/api/referral',
    b'/api/local-backup',
]


def entries(jar):
    with zipfile.ZipFile(jar) as z:
        return {n: z.read(n) for n in z.namelist() if not n.endswith('/')}


def scan(data_map, markers):
    hits = []
    for name, data in data_map.items():
        if not name.endswith('.class'):
            continue
        for marker in markers:
            if marker in data:
                hits.append((name, marker.decode('utf-8', 'ignore')))
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--main')
    ap.add_argument('--updater', required=True)
    args = ap.parse_args()
    fail = []

    upd = entries(args.updater)

    # 1) 更新器不得含主程序代码/样式/图标
    own_biz = [n for n in upd if n.startswith(UPDATER_PREFIXES) and n.endswith('.class')]
    print('  更新器业务 class:', len(own_biz),
          '（u/ %d，updater/ %d）' % (
              sum(1 for n in own_biz if n.startswith('u/')),
              sum(1 for n in own_biz if n.startswith('updater/'))))
    if len(own_biz) < 100:
        fail.append('更新器业务 class 太少，混淆可能没生效')
    for prefix in ('business/', 'x/', 'icons/'):
        leaked = [n for n in upd if n.startswith(prefix)]
        if leaked:
            fail.append('更新器里出现主程序命名空间 %s（%d 项，例：%s）'
                        % (prefix, len(leaked), leaked[:3]))
    h = scan(upd, MAIN_MARKERS)
    if h:
        fail.append('更新器里出现主程序类/样式：%s' % h[:5])

    # 2) 更新器入口与反射任务类必须保名
    for required in ('updater/main/updater/UpdaterMain.class',
                     'updater/main/down/MainDownLoadFromTdx.class',
                     'updater/main/down/MainBackfillHistoryFromTdx.class',
                     'updater/fetchdata/tdx/min/MinDataUpdater.class'):
        if required not in upd:
            fail.append('更新器缺少入口/任务类：' + required)
    mf = upd.get('META-INF/MANIFEST.MF', b'').decode('utf-8', 'ignore')
    if 'updater.main.updater.UpdaterMain' not in mf:
        fail.append('更新器 Main-Class 不对')

    # 3) 图标：更新器只用 uicons/updater-*.png
    upd_icons = [n for n in upd if n.endswith('.png')]
    bad_icons = [n for n in upd_icons if not n.startswith('uicons/updater-')]
    if bad_icons:
        fail.append('更新器里有非专属图标：%s' % bad_icons[:5])
    if not upd_icons:
        fail.append('更新器没有打包自己的图标')
    print('  更新器专属图标:', len(upd_icons), '个')

    # 4) 抓取特征必须存在于更新器包内
    found = set()
    for name, data in upd.items():
        if name.endswith('.class'):
            for m in FETCH_MARKERS:
                if m in data:
                    found.add(m)
    print('  更新器内抓取特征命中: %d/%d' % (len(found), len(FETCH_MARKERS)))
    if len(found) < 6:
        fail.append('更新器内抓取特征命中太少，抓取能力可能被误删')

    # 4b) 身份指纹：更新器里不许出现对方的品牌、账户体系、授权/加密常量。
    # 这几条是「换个包名混淆」挡不住的 —— 字符串会原样留在常量池里。
    print('  更新器身份指纹:', end=' ')
    fp_hits = []
    for marker in OWN_FINGERPRINTS:
        hit = [n for n, d in upd.items() if marker in d]
        if hit:
            fp_hits.append((marker.decode('utf-8', 'ignore'), hit[:2]))
    if fp_hits:
        fail.append('更新器里出现对方的身份指纹：%s' % fp_hits[:6])
    else:
        print('%d 项全无' % len(OWN_FINGERPRINTS))

    # 4c) 构建坐标不进发布物：META-INF/maven/<自家 groupId>/ 会把 groupId 和整份 pom 带出去
    own_coord = [n for n in upd if n.startswith('META-INF/maven/')
                 and not n.startswith(('META-INF/maven/com.alibaba.', 'META-INF/maven/io.github.',
                                       'META-INF/maven/org.', 'META-INF/maven/de.'))]
    if own_coord:
        fail.append('更新器带了自家的构建坐标：%s' % own_coord[:3])

    if args.main:
        m = entries(args.main)
        # 5) 主程序不得含更新器命名空间/图标/样式
        for prefix in UPDATER_PREFIXES:
            leaked = [n for n in m if n.startswith(prefix)]
            if leaked:
                fail.append('主程序里出现更新器命名空间 %s（%d 项，例：%s）'
                            % (prefix, len(leaked), leaked[:3]))
        h = scan(m, FETCH_MARKERS)
        if h:
            fail.append('主程序里仍有抓取代码痕迹：%s' % h[:8])
        else:
            print('  主程序抓取特征命中: 0（干净）')
        # 反向指纹：主程序里不许出现更新器的实现类 / 工程坐标，免得日后有人拿主
        # 程序反推出另一个工程。
        #
        # 例外：StockDataUpdater.jar 是「用 AI 编译开源项目」方案里交给用户的**外部
        # 插件交付文件名**，界面步骤与挂载说明必须原样写出来（方案 3/4 共用规格）。
        # 它是把更新器当外部插件挂载的凭据，不是代码耦合；一律禁掉反而会让界面与
        # 规格书对不上。所以只允许这一整串文件名出现，其它形态（类路径、带版本号
        # 的编译产物、工程目录名）一律算泄露。
        UPDATER_IMPL_MARKERS = (
            b'UpdaterInfo', b'UpdaterMain', b'updater.main.', b'data-updater',
        )
        rev = [mk for mk in UPDATER_IMPL_MARKERS
               if any(mk in d for d in m.values())]
        DELIVERY_NAME = b'StockDataUpdater.jar'
        stray = []
        for name, d in m.items():
            pos = 0
            while True:
                pos = d.find(b'StockDataUpdater', pos)
                if pos < 0:
                    break
                if d[pos:pos + len(DELIVERY_NAME)] != DELIVERY_NAME:
                    stray.append((name, d[pos:pos + 40].decode('utf-8', 'ignore')))
                    break
                pos += len(DELIVERY_NAME)
        if rev:
            fail.append('主程序里出现更新器实现类/工程标识：%s'
                        % [x.decode('utf-8', 'ignore') for x in rev])
        if stray:
            fail.append('主程序里出现非交付文件名形态的更新器标识：%s' % stray[:5])
        if not rev and not stray:
            print('  主程序里的更新器标识: 0（干净；仅交付文件名 %s）'
                  % DELIVERY_NAME.decode())
        main_icons = [n for n in m if n.endswith('.png') and n.startswith('icons/app-')]
        if not main_icons:
            fail.append('主程序没有自己的 app 图标')
        if any(n.startswith('uicons/') for n in m):
            fail.append('主程序里带了更新器图标')
        print('  主程序专属图标:', len(main_icons), '个')

        # 6) class 路径零交集（第三方库除外）
        def own_classes(d):
            return {n for n in d if n.endswith('.class')
                    and (n.startswith(('business/', 'x/')) or n.startswith(('updater/', 'u/')))}
        overlap = own_classes(m) & own_classes(upd)
        if overlap:
            fail.append('两个 jar 的业务 class 有交集：%s' % sorted(overlap)[:5])
        print('  业务 class 交集:', len(overlap))

    if fail:
        print('\n  隔离断言失败：', file=sys.stderr)
        for f in fail:
            print('   - ' + f, file=sys.stderr)
        sys.exit(1)
    print('  隔离断言通过')


if __name__ == '__main__':
    main()
