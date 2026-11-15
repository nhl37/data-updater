package updater.main.updater;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 台账表格的数据源：默认全量顺序展示，可按状态筛选、按代码搜索。
 *
 * <p>刷新策略：跑起来以后状态一直在变，但<b>行数不变</b>（清单在开跑前就铺好了），
 * 所以刷新只是 {@link #fireTableDataChanged()} 重画，不重建行 —— 用户选中的那一行不会跳。
 * 只有在筛选条件变化、或者清单本身变了（任务刚起、切到历史台账）时才重建视图。</p>
 */
public final class LedgerTableModel extends AbstractTableModel {

    /** 台账上方的筛选胶囊 */
    public enum Filter {
        ALL("全部"),
        FAILED("失败"),
        PENDING("未完成"),
        SKIPPED("跳过"),
        DONE("完成");

        public final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private static final String[] COLUMNS = {"代码 / 项目", "状态", "说明 / 原因", "用时"};

    private List<UpdateLedger.Item> all = new ArrayList<UpdateLedger.Item>();
    private List<UpdateLedger.Item> view = new ArrayList<UpdateLedger.Item>();
    private Filter filter = Filter.ALL;
    private String search = "";

    /** 换一份数据（切任务、开跑、看历史台账） */
    public void setItems(List<UpdateLedger.Item> items) {
        this.all = items == null ? new ArrayList<UpdateLedger.Item>() : items;
        rebuild();
    }

    /** 状态变了：只重画，不重建 */
    public void refresh() {
        if (!view.isEmpty()) {
            fireTableDataChanged();
        }
    }

    public void setFilter(Filter filter) {
        this.filter = filter == null ? Filter.ALL : filter;
        rebuild();
    }

    public Filter filter() {
        return filter;
    }

    public void setSearch(String text) {
        String next = text == null ? "" : text.trim().toUpperCase();
        if (next.equals(search)) {
            return;
        }
        this.search = next;
        rebuild();
    }

    /** 当前视图里每一行的原始数据（渲染器按状态上色要用） */
    public UpdateLedger.Item itemAt(int row) {
        if (row < 0 || row >= view.size()) {
            return null;
        }
        return view.get(row);
    }

    public List<UpdateLedger.Item> viewItems() {
        return view;
    }

    /** 模型里一共有多少行（不含筛选）—— 用来判断台账是不是又长出新行了 */
    public int allSize() {
        return all.size();
    }

    private void rebuild() {
        List<UpdateLedger.Item> next = new ArrayList<UpdateLedger.Item>();
        for (UpdateLedger.Item item : all) {
            if (matches(item)) {
                next.add(item);
            }
        }
        view = next;
        fireTableDataChanged();
    }

    private boolean matches(UpdateLedger.Item item) {
        if (item == null) {
            return false;
        }
        if (!search.isEmpty() && !item.key.toUpperCase().contains(search)) {
            return false;
        }
        switch (filter) {
            case FAILED:
                return item.status == UpdateLedger.Status.FAILED;
            case PENDING:
                return item.status == UpdateLedger.Status.PENDING
                        || item.status == UpdateLedger.Status.RUNNING;
            case SKIPPED:
                return item.status == UpdateLedger.Status.SKIPPED;
            case DONE:
                return item.status == UpdateLedger.Status.DONE;
            default:
                return true;
        }
    }

    @Override
    public int getRowCount() {
        return view.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        UpdateLedger.Item item = itemAt(rowIndex);
        if (item == null) {
            return "";
        }
        switch (columnIndex) {
            case 0:
                return item.key;
            case 1:
                return item.status.label;
            case 2:
                return item.text();
            case 3:
                return item.millis <= 0 ? "" : (item.millis < 1000
                        ? item.millis + " 毫秒"
                        : String.format(java.util.Locale.ROOT, "%.1f 秒", item.millis / 1000.0));
            default:
                return "";
        }
    }
}
