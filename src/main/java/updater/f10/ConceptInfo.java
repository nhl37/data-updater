package updater.f10;

import java.util.ArrayList;
import java.util.List;

/**
 * 个股的所属板块与核心题材（东财 F10「核心题材」页的数据）。
 *
 * @param boards 所属板块：行业三级 + 地域 + 概念
 * @param themes 核心题材：经营范围、主营业务、题材要点等带说明的条目
 */
public record ConceptInfo(List<Board> boards, List<Theme> themes) {

    /**
     * 一个板块标签
     *
     * @param name     板块名（银行Ⅱ、上海板块、区块链…）
     * @param rank     东财给的排序（1 是行业一级，越小越「主干」）
     * @param category 归类：行业 / 地域 / 概念
     */
    public record Board(String name, int rank, String category) {
    }

    /**
     * 一条核心题材
     *
     * @param keyword 题材关键词（经营范围、主营业务、参股银行…）
     * @param content 说明正文
     */
    public record Theme(String keyword, String content) {
    }

    public static ConceptInfo empty() {
        return new ConceptInfo(new ArrayList<>(), new ArrayList<>());
    }

    public boolean isEmpty() {
        return (boards == null || boards.isEmpty()) && (themes == null || themes.isEmpty());
    }

    /**
     * 按类别取出板块名，界面上分行显示
     */
    public List<Board> byCategory(String category) {
        List<Board> result = new ArrayList<>();
        if (boards == null) {
            return result;
        }
        for (Board board : boards) {
            if (board.category().equals(category)) {
                result.add(board);
            }
        }
        result.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        return result;
    }
}
