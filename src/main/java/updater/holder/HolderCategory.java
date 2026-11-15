package updater.holder;

/**
 * 流通大股东的四类归属：国家队 / 金融机构 / 普通机构 / 个人。
 *
 * <p>东财的 {@code HOLDER_NEWTYPE} 只给了「基金 / QFII / 保险 / 社保 / 券商 / 信托 / 个人 / 其他」，
 * 其中「其他」占了最大一块（约六成），里面既有一般法人、国资委、员工持股计划，
 * 也有私募基金和银行 —— 光看类型字段没法直接分成四类，所以按
 * <b>类型 → 股东性质 → 名称关键词</b> 三层依次判断。</p>
 *
 * <p>判断顺序刻意定成「先排除、再归入」：</p>
 * <ol>
 *   <li>个人：类型或性质直接写着「个人」；</li>
 *   <li>特殊账户（信用账户、破产处置专户、回购专户、员工持股计划）：一律普通机构，
 *       它们不是投资主体，混进金融机构会把「谁在买」算歪；</li>
 *   <li>国家队：社保 / 养老金 / 汇金 / 证金 / 国新 / 诚通 / 大基金这类名字，或类型就是社保、国家队；</li>
 *   <li>金融机构：基金（公募私募）、QFII、保险、券商、信托、银行、理财；</li>
 *   <li>剩下的（一般法人、地方国资、投资公司、陆股通名义持有人）归普通机构。</li>
 * </ol>
 *
 * <p>国家队的关键词写死在 {@link #NATIONAL_KEYS}，要扩就改这一处：判断只看名字包含关系，
 * 不需要维护枚举。宁可漏判成「普通机构」，也不要用模糊词（如「诚通」）把产业基金误抓进来。</p>
 */
public enum HolderCategory {

    NATIONAL("国家队"),
    FINANCIAL("金融机构"),
    INSTITUTION("普通机构"),
    INDIVIDUAL("个人");

    public final String label;

    HolderCategory(String label) {
        this.label = label;
    }

    /** 数据库里存的是序号，改顺序会让老库读错，所以只用 ordinal() 存、绝不改名 */
    public static HolderCategory of(int code) {
        HolderCategory[] all = values();
        return code >= 0 && code < all.length ? all[code] : INSTITUTION;
    }

    /** 国家队名单：按名称包含判断 */
    private static final String[] NATIONAL_KEYS = {
            // 只认「基本养老保险基金 / 社保 / 年金」这类国家主体，
            // 不写裸的「养老金」——基金公司发的「XX 养老金产品」名字里也有这两字，会被误算成国家队
            "全国社会保障基金", "社会保障基金", "社保基金", "基本养老保险", "养老保险基金",
            "企业年金", "中央汇金", "汇金", "中国证券金融", "证金公司",
            "国新投资", "中国国新", "中国诚通", "梧桐树投资",
            "国家集成电路产业投资基金", "国家制造业转型升级基金", "国家军民融合产业投资基金",
            "国家中小企业发展基金", "国家产业投资基金", "中央企业乡村产业投资基金",
            "中国国有企业结构调整基金", "国家绿色发展基金", "国家科技重大专项成果转化基金",
            "中华人民共和国财政部"
    };

    /** 明确不是投资主体的账户：先于「金融机构」判断，否则「XX证券客户信用账户」会被算成券商 */
    private static final String[] NOMINEE_KEYS = {
            "客户信用交易担保证券账户", "破产企业财产处置专用账户", "回购专用证券账户",
            "员工持股计划", "约定购回专用账户"
    };

    private static final String[] FINANCIAL_KEYS = {
            "基金管理", "私募基金", "证券投资基金", "资产管理", "理财",
            "证券", "保险", "信托", "银行", "期货", "QFII"
    };

    /**
     * 判断一位股东属于哪一类。
     *
     * @param newType 东财 HOLDER_NEWTYPE（基金/QFII/保险/社保/券商/信托/个人/其他）
     * @param nature  东财 HOLDER_NATURE（证券投资基金/私募基金/保险产品/投资公司/其它…），可为空
     * @param name    股东名称
     */
    public static HolderCategory classify(String newType, String nature, String name) {
        String type = newType == null ? "" : newType.trim();
        String nat = nature == null ? "" : nature.trim();
        String who = name == null ? "" : name.trim();

        if ("个人".equals(type) || "个人".equals(nat)) {
            return INDIVIDUAL;
        }
        for (String key : NOMINEE_KEYS) {
            if (who.contains(key)) {
                return INSTITUTION;
            }
        }
        if ("社保".equals(type) || "国家队".equals(type) || "基本养老基金".equals(nat)
                || "企业年金".equals(nat) || "社保基金".equals(nat)) {
            return NATIONAL;
        }
        for (String key : NATIONAL_KEYS) {
            if (who.contains(key)) {
                return NATIONAL;
            }
        }
        switch (type) {
            case "基金":
            case "QFII":
            case "保险":
            case "券商":
            case "信托":
            case "银行":
                return FINANCIAL;
            default:
                break;
        }
        switch (nat) {
            case "金融":
            case "私募基金":
            case "基金管理公司":
            case "其他理财产品":
            case "证券投资基金":
            case "保险公司":
            case "保险产品":
                return FINANCIAL;
            default:
                break;
        }
        for (String key : FINANCIAL_KEYS) {
            if (who.contains(key)) {
                return FINANCIAL;
            }
        }
        return INSTITUTION;
    }

    /**
     * 界面上「类型」一列显示的文字：东财类型不够用时补几个能一眼看懂的名字。
     *
     * <p>陆股通（香港中央结算）在数据里是「其他 / 其它」，但它其实是北向资金的名义持有人，
     * 单独标出来比混在普通机构里有用；同理私募基金在「其他」里也该显形。</p>
     */
    public static String typeLabel(String newType, String nature, String name) {
        String who = name == null ? "" : name.trim();
        if (who.contains("香港中央结算")) {
            return "陆股通";
        }
        String type = newType == null ? "" : newType.trim();
        if (!type.isEmpty() && !"其他".equals(type)) {
            return type;
        }
        String nat = nature == null ? "" : nature.trim();
        if ("私募基金".equals(nat)) {
            return "私募";
        }
        if (!nat.isEmpty() && !"其它".equals(nat)) {
            return nat;
        }
        return "其他";
    }
}
