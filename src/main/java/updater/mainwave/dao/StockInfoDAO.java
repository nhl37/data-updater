
package updater.mainwave.dao;

import updater.db.DBException;
import updater.name.StockNameStore;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票信息（代码 → 名称）数据访问对象。
 *
 * <p>数据已经单独成库（{@link StockNameStore}），不再和主升浪图片共用
 * {@code main_wave_images.db}。所有读写都经 {@link StockNameStore#open()}。</p>
 *
 * <p><b>名称库是挂载才启用的</b>：没挂载时 {@code open()} 给的是一个内存空库，
 * 这里的查询一律返回空、写入落在内存里即消失 —— 上层拿到的就是“没有名称”，
 * 不会抛异常，也不会在磁盘上凭空建出库文件。</p>
 */
public class StockInfoDAO {
    
    /**
     * 股票信息实体类
     */
    public static class StockInfo {
        private int id;
        private String stockCode;
        private String stockName;
        private String lastUpdate;
        private String dataSource;
        private String createdTime;
        
        // Getter和Setter方法
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        
        public String getStockCode() { return stockCode; }
        public void setStockCode(String stockCode) { this.stockCode = stockCode; }
        
        public String getStockName() { return stockName; }
        public void setStockName(String stockName) { this.stockName = stockName; }
        
        public String getLastUpdate() { return lastUpdate; }
        public void setLastUpdate(String lastUpdate) { this.lastUpdate = lastUpdate; }
        
        public String getDataSource() { return dataSource; }
        public void setDataSource(String dataSource) { this.dataSource = dataSource; }
        
        public String getCreatedTime() { return createdTime; }
        public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }
        
        @Override
        public String toString() {
            return "StockInfo{" +
                   "id=" + id +
                   ", stockCode='" + stockCode + '\'' +
                   ", stockName='" + stockName + '\'' +
                   ", lastUpdate='" + lastUpdate + '\'' +
                   ", dataSource='" + dataSource + '\'' +
                   ", createdTime='" + createdTime + '\'' +
                   '}';
        }
    }
    
    /**
     * 初始化股票信息表（名称库由 {@link StockNameStore} 统一管理，这里只保证表在）。
     *
     * <p>失败<b>不抛</b>：名称只影响界面显示，不能让主升浪画面 / 因子打分因此打不开。</p>
     */
    private void initTable() throws DBException {
        StockNameStore.ensureReady();
        try (Connection conn = StockNameStore.open();
             PreparedStatement pstmt = conn.prepareStatement(StockNameStore.CREATE_TABLE)) {
            pstmt.executeUpdate();
        } catch (SQLException e) {
            java.io.File nameDb = StockNameStore.dbFile();
            System.err.println("初始化股票信息表失败: " + e.getMessage()
                    + "（名称库: " + (nameDb == null ? "未挂载" : nameDb.getAbsolutePath()) + "）");
        }
    }
    
    public StockInfoDAO() throws DBException {
        initTable();
    }
    
    /**
     * 获取所有股票信息
     */
    public List<StockInfo> getAllStockInfos() throws DBException {
        String sql = "SELECT id, stock_code, stock_name, last_update, data_source, created_time " +
                     "FROM " + StockNameStore.TABLE + " ORDER BY stock_code";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        List<StockInfo> stockInfos = new ArrayList<>();
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            
            while (rs.next()) {
                StockInfo stockInfo = new StockInfo();
                stockInfo.setId(rs.getInt("id"));
                stockInfo.setStockCode(rs.getString("stock_code"));
                stockInfo.setStockName(rs.getString("stock_name"));
                stockInfo.setLastUpdate(rs.getString("last_update"));
                stockInfo.setDataSource(rs.getString("data_source"));
                stockInfo.setCreatedTime(rs.getString("created_time"));
                stockInfos.add(stockInfo);
            }
            
            return stockInfos;
            
        } catch (SQLException e) {
            throw new DBException("获取股票信息列表失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    
    /**
     * 根据股票代码获取股票信息。
     *
     * <p>库里的 stock_code 是带市场前缀的（{@code sh600000} / {@code sz000001}，
     * 这样 sh000001 与 sz000001 才分得开），而调用方常常只有纯 6 位代码。所以先精确匹配，
     * 查不到再按「去掉 sh/sz/bj 后相等」兜一次，两种写法都能命中。</p>
     */
    public StockInfo getStockInfoByCode(String stockCode) throws DBException {
        if (stockCode == null || stockCode.trim().isEmpty()) {
            return null;
        }
        String code = stockCode.trim();
        StockInfo info = queryOne("stock_code = ?", code);
        if (info != null) {
            return info;
        }
        String digits = code.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return queryOne("REPLACE(REPLACE(REPLACE(LOWER(stock_code), 'sh', ''), 'sz', ''), 'bj', '') = ?",
                digits);
    }

    /** 按单个条件查一条，查不到返回 null */
    private StockInfo queryOne(String where, String param) throws DBException {
        String sql = "SELECT id, stock_code, stock_name, last_update, data_source, created_time " +
                     "FROM " + StockNameStore.TABLE + " WHERE " + where;

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, param);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                StockInfo stockInfo = new StockInfo();
                stockInfo.setId(rs.getInt("id"));
                stockInfo.setStockCode(rs.getString("stock_code"));
                stockInfo.setStockName(rs.getString("stock_name"));
                stockInfo.setLastUpdate(rs.getString("last_update"));
                stockInfo.setDataSource(rs.getString("data_source"));
                stockInfo.setCreatedTime(rs.getString("created_time"));
                return stockInfo;
            }

            return null;

        } catch (SQLException e) {
            throw new DBException("根据股票代码获取股票信息失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    
    /**
     * 根据股票名称搜索股票信息
     */
    public List<StockInfo> searchStockInfoByName(String keyword) throws DBException {
        String sql = "SELECT id, stock_code, stock_name, last_update, data_source, created_time " +
                     "FROM " + StockNameStore.TABLE + 
                     " WHERE stock_name LIKE ? ORDER BY stock_code";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        List<StockInfo> stockInfos = new ArrayList<>();
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, "%" + keyword + "%");
            rs = pstmt.executeQuery();
            
            while (rs.next()) {
                StockInfo stockInfo = new StockInfo();
                stockInfo.setId(rs.getInt("id"));
                stockInfo.setStockCode(rs.getString("stock_code"));
                stockInfo.setStockName(rs.getString("stock_name"));
                stockInfo.setLastUpdate(rs.getString("last_update"));
                stockInfo.setDataSource(rs.getString("data_source"));
                stockInfo.setCreatedTime(rs.getString("created_time"));
                stockInfos.add(stockInfo);
            }
            
            return stockInfos;
            
        } catch (SQLException e) {
            throw new DBException("根据股票名称搜索股票信息失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    
    /**
     * 添加或更新股票信息
     * 使用INSERT OR REPLACE确保股票代码唯一
     */
    public void upsertStockInfo(String stockCode, String stockName, String dataSource) throws DBException {
        String sql = "INSERT OR REPLACE INTO " + StockNameStore.TABLE + 
                     " (stock_code, stock_name, data_source, last_update) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, stockCode);
            pstmt.setString(2, stockName);
            pstmt.setString(3, dataSource);
            pstmt.executeUpdate();
            
        } catch (SQLException e) {
            throw new DBException("添加或更新股票信息失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, null);
        }
    }
    
    /**
     * 批量添加或更新股票信息
     */
    public void batchUpsertStockInfo(List<StockInfo> stockInfos) throws DBException {
        String sql = "INSERT OR REPLACE INTO " + StockNameStore.TABLE + 
                     " (stock_code, stock_name, data_source, last_update) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        
        try {
            conn = StockNameStore.open();
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(sql);
            
            for (StockInfo stockInfo : stockInfos) {
                pstmt.setString(1, stockInfo.getStockCode());
                pstmt.setString(2, stockInfo.getStockName());
                pstmt.setString(3, stockInfo.getDataSource());
                pstmt.addBatch();
            }
            
            pstmt.executeBatch();
            conn.commit();
            
        } catch (SQLException e) {
            try {
                if (conn != null) conn.rollback();
            } catch (SQLException ex) {
                // ignore
            }
            throw new DBException("批量添加或更新股票信息失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, null);
        }
    }
    
    /**
     * 删除股票信息
     */
    public void deleteStockInfo(String stockCode) throws DBException {
        String sql = "DELETE FROM " + StockNameStore.TABLE + " WHERE stock_code = ?";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, stockCode);
            pstmt.executeUpdate();
            
        } catch (SQLException e) {
            throw new DBException("删除股票信息失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, null);
        }
    }
    
    /**
     * 获取股票代码列表
     */
    public List<String> getStockCodes() throws DBException {
        String sql = "SELECT stock_code FROM " + StockNameStore.TABLE + " ORDER BY stock_code";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        List<String> stockCodes = new ArrayList<>();
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            
            while (rs.next()) {
                stockCodes.add(rs.getString("stock_code"));
            }
            
            return stockCodes;
            
        } catch (SQLException e) {
            throw new DBException("获取股票代码列表失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    
    /**
     * 获取股票代码到名称的映射
     */
    public java.util.Map<String, String> getStockCodeNameMap() throws DBException {
        String sql = "SELECT stock_code, stock_name FROM " + StockNameStore.TABLE;
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        java.util.Map<String, String> map = new java.util.HashMap<>();
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            
            while (rs.next()) {
                map.put(rs.getString("stock_code"), rs.getString("stock_name"));
            }
            
            return map;
            
        } catch (SQLException e) {
            throw new DBException("获取股票代码名称映射失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    
    /**
     * 关闭数据库资源
     */
    private void closeResources(Connection conn, PreparedStatement pstmt, ResultSet rs) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException e) {
            System.err.println("关闭ResultSet失败: " + e.getMessage());
        }
        try {
            if (pstmt != null) pstmt.close();
        } catch (SQLException e) {
            System.err.println("关闭PreparedStatement失败: " + e.getMessage());
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException e) {
            System.err.println("关闭Connection失败: " + e.getMessage());
        }
    }
    /**
     * 获取股票信息表统计信息
     */
    public String getStockInfoStats() throws DBException {
        String countSql = "SELECT COUNT(*) as total FROM " + StockNameStore.TABLE;
        String sampleSql = "SELECT stock_code, stock_name FROM " + StockNameStore.TABLE + " LIMIT 5";
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        
        try {
            conn = StockNameStore.open();
            
            // 获取总数
            pstmt = conn.prepareStatement(countSql);
            rs = pstmt.executeQuery();
            int total = rs.next() ? rs.getInt("total") : 0;
            
            rs.close();
            pstmt.close();
            
            // 获取样本
            pstmt = conn.prepareStatement(sampleSql);
            rs = pstmt.executeQuery();
            
            StringBuilder stats = new StringBuilder();
            stats.append("股票信息表统计:\n");
            stats.append("  总记录数: ").append(total).append("\n");
            stats.append("  前5条记录:\n");
            
            int count = 0;
            while (rs.next() && count < 5) {
                String code = rs.getString("stock_code");
                String name = rs.getString("stock_name");
                stats.append("    ").append(code).append(" -> ").append(name).append("\n");
                count++;
            }
            
            return stats.toString();
            
        } catch (SQLException e) {
            throw new DBException("获取股票信息表统计失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
    /**
     * 获取股票代码到名称的映射（增强版：包含多种格式的键）
     */
    public java.util.Map<String, String> getEnhancedStockCodeNameMap() throws DBException {
        String sql = "SELECT stock_code, stock_name FROM " + StockNameStore.TABLE;
        
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        java.util.Map<String, String> map = new java.util.HashMap<>();
        
        try {
            conn = StockNameStore.open();
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            
            while (rs.next()) {
                String stockCode = rs.getString("stock_code");
                String stockName = rs.getString("stock_name");
                
                // 1. 原始代码作为键
                map.put(stockCode, stockName);
                
                // 2. 大写版本
                map.put(stockCode.toUpperCase(), stockName);
                
                // 3. 小写版本（如果原始不是小写）
                if (!stockCode.equals(stockCode.toLowerCase())) {
                    map.put(stockCode.toLowerCase(), stockName);
                }
                
                // 4. 移除前缀后的数字部分
                String digitsOnly = stockCode.replaceAll("[^0-9]", "");
                if (!digitsOnly.isEmpty()) {
                    map.put(digitsOnly, stockName);
                    
                    // 5. 对于沪市股票（sh开头），添加6开头的数字格式
                    if (stockCode.toLowerCase().startsWith("sh") && digitsOnly.startsWith("6")) {
                        map.put("6" + digitsOnly.substring(1), stockName);
                    }
                    // 6. 对于深市股票（sz开头），添加0开头的数字格式
                    else if (stockCode.toLowerCase().startsWith("sz") && digitsOnly.startsWith("0")) {
                        map.put("0" + digitsOnly.substring(1), stockName);
                    }
                }
            }
            
            System.out.println("增强版股票映射创建完成，大小: " + map.size());
            return map;
            
        } catch (SQLException e) {
            throw new DBException("获取增强版股票代码名称映射失败: " + e.getMessage(), e);
        } finally {
            closeResources(conn, pstmt, rs);
        }
    }
}