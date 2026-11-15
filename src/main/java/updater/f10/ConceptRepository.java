package updater.f10;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 板块与核心题材：读东财 F10 的「核心题材」接口（{@code CoreConception/PageAjax}）。
 *
 * <p>返回两块：{@code ssbk} 是所属板块（行业三级 + 地域 + 概念，多的有 30 个），
 * {@code hxtc} 是核心题材（经营范围、主营业务、题材要点，带正文说明）。</p>
 *
 * <p>板块变化很慢，缓存 3 天。分类是从名称和排序推出来的：东财行业板块名带 Ⅰ/Ⅱ/Ⅲ 后缀，
 * 地域板块以「板块」结尾（上海板块、江苏板块），其余算概念。</p>
 */
public final class ConceptRepository {

    private static final String URL =
            "https://emweb.securities.eastmoney.com/PC_HSF10/CoreConception/PageAjax?code=%s";

    private ConceptRepository() {
    }

    public static ConceptInfo load(String code, boolean refresh) throws Exception {
        String plain = F10Api.plain(code);
        if (plain.isEmpty()) {
            return ConceptInfo.empty();
        }
        String payload = refresh ? null : F10Store.conceptCache(plain, F10Store.TTL_CONCEPT);
        if (payload == null) {
            payload = F10Api.get(String.format(URL, F10Api.marketCode(plain)),
                    "https://emweb.securities.eastmoney.com/pc_hsf10/pages/index.html?type=web&code="
                            + F10Api.marketCode(plain));
            F10Store.putConceptCache(plain, payload);
        }
        return parse(payload);
    }

    static ConceptInfo parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return ConceptInfo.empty();
        }
        JSONObject root = JSONObject.parseObject(payload);
        if (root == null) {
            return ConceptInfo.empty();
        }

        List<ConceptInfo.Board> boards = new ArrayList<>();
        JSONArray ssbk = root.getJSONArray("ssbk");
        if (ssbk != null) {
            for (int i = 0; i < ssbk.size(); i++) {
                JSONObject item = ssbk.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = item.getString("BOARD_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int rank = item.getIntValue("BOARD_RANK", i + 1);
                boards.add(new ConceptInfo.Board(name.trim(), rank, categoryOf(name, rank)));
            }
        }

        List<ConceptInfo.Theme> themes = new ArrayList<>();
        JSONArray hxtc = root.getJSONArray("hxtc");
        if (hxtc != null) {
            for (int i = 0; i < hxtc.size(); i++) {
                JSONObject item = hxtc.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                String keyword = item.getString("KEYWORD");
                String content = item.getString("MAINPOINT_CONTENT");
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                themes.add(new ConceptInfo.Theme(keyword.trim(), content == null ? "" : content.trim()));
            }
        }
        return new ConceptInfo(boards, themes);
    }

    /**
     * 板块归类：行业（东财一级到三级，名字常带 Ⅰ/Ⅱ/Ⅲ）／地域（xx板块）／概念
     */
    private static String categoryOf(String name, int rank) {
        if (name.endsWith("板块")) {
            return "地域";
        }
        if (rank <= 3 || name.endsWith("Ⅰ") || name.endsWith("Ⅱ") || name.endsWith("Ⅲ")) {
            return "行业";
        }
        return "概念";
    }
}
