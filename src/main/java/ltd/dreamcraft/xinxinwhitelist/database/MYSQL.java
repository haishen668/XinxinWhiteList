package ltd.dreamcraft.xinxinwhitelist.database;

import com.xinxin.BotApi.BotBind;
import ltd.dreamcraft.xinxinwhitelist.BotActionLocal;
import ltd.dreamcraft.xinxinwhitelist.XinxinWhiteList;
import ltd.dreamcraft.xinxinwhitelist.beans.GroupMember;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author haishen668
 */
public class MYSQL implements PlayerData {

    private static Connection connection;

    public static Connection getConnection() {
        return connection;
    }

    public MYSQL() {
        initConnection();
    }

    private void initConnection() {
        String driver = "com.mysql.cj.jdbc.Driver";
        try {
            Class.forName(driver);
        } catch (Exception ignored) {
            driver = "com.mysql.jdbc.Driver";
            XinxinWhiteList.getInstance().getLogger().info("Driver class '" + driver + "' not found! Falling back to legacy MySQL driver (com.mysql.jdbc.Driver)");
        }
        FileConfiguration config = XinxinWhiteList.getInstance().getConfig();
        String hostname = config.getString("database.hostname");
        String port = config.getString("database.port");
        String username = config.getString("database.username");
        String password = config.getString("database.password");
        String database = config.getString("database.database");
        String useSSL = config.getString("database.useSSL");
        String url = "jdbc:mysql://" + hostname + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&useSSL=" + useSSL;
        try {
            connection = DriverManager.getConnection(url, username, password);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS xxw_players (name VARCHAR(255), qq VARCHAR(255))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS xxw_blackplayers (id INT PRIMARY KEY, qq VARCHAR(255) UNIQUE)");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void checkConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            initConnection();
        }
    }

    @Override
    public String getPlayerName(String playerName) {
        try {
            checkConnection();
            String sql = "SELECT * FROM xxw_players WHERE name = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, playerName);
                try (ResultSet rs = preparedStatement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("qq");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void modifyPlayerData(String playerName, Long newQQ) {
        try {
            checkConnection();
            String sql = "UPDATE xxw_players SET qq = ? WHERE name = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, newQQ);
                preparedStatement.setString(2, playerName);
                preparedStatement.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getPlayerByQQ(String qq) {
        try {
            checkConnection();
            String sql = "SELECT name FROM xxw_players WHERE qq = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, Long.parseLong(qq));
                try (ResultSet rs = preparedStatement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("name");
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void addPlayerData(String playerName, Long qq) {
        try {
            checkConnection();
            String sql = "INSERT INTO xxw_players (name, qq) VALUES (?, ?)";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, playerName);
                preparedStatement.setString(2, String.valueOf(qq));
                preparedStatement.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean removePlayerByID(String playerName) {
        try {
            checkConnection();
            String sql = "DELETE FROM xxw_players WHERE name = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, playerName);
                preparedStatement.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean removePlayerDataByQQ(long qq) {
        try {
            checkConnection();
            String sql = "DELETE FROM xxw_players WHERE qq = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setString(1, String.valueOf(qq));
                preparedStatement.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void removeWhiteListByGroupId(long groupId) {
        Bukkit.getScheduler().runTaskAsynchronously(XinxinWhiteList.getInstance(), () -> {
            AtomicInteger successCount = new AtomicInteger(); // 成功计数器
            List<GroupMember> groupMemberList = BotActionLocal.getGroupMemberList(groupId);
            ArrayList<Long> memberIdList = new ArrayList<>();
            for (GroupMember member : groupMemberList) {
                long userId = member.getUserId();
                memberIdList.add(userId);
            }

            String query = "SELECT name, qq FROM xxw_players";
            Statement statement = null;
            ResultSet resultSet = null;
            try {
                checkConnection();
                statement = connection.createStatement();
                resultSet = statement.executeQuery(query);

                while (resultSet.next()) {
                    String playerName = resultSet.getString("name");
                    long qqId = resultSet.getLong("qq");
                    if (!memberIdList.contains(qqId)) {
                        boolean b = removePlayerByID(playerName.toLowerCase());
                        if (b) {
                            successCount.getAndIncrement(); // 每次成功删除时递增计数器
                            XinxinWhiteList.getInstance().getLogger().info("§a[XXW] §c玩家" + playerName + "的绑定数据已经成功删除");
                            //黑名单定制功能
                            if (!checkIsBaned(qqId)) {
                                banQQ(qqId);
                            }
                        } else {
                            XinxinWhiteList.getInstance().getLogger().warning("§a[XXW] §c玩家" + playerName + "的绑定数据删除失败");
                        }
                    }
                }

                if (successCount.get() > 0) {
                    XinxinWhiteList.getInstance().getLogger().info("§a[XXW] §c成功删除了" + successCount.get() + "个玩家的绑定数据");
                } else {
                    XinxinWhiteList.getInstance().getLogger().info("§a[XXW] §c未删除任何玩家的绑定数据");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                XinxinWhiteList.getInstance().getLogger().severe("§a[XXW] §c无法从数据库中获取玩家数据");
            } finally {
                try {
                    if (resultSet != null) resultSet.close();
                    if (statement != null) statement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void reload() {
        // Method implementation here
    }

    @Override
    public Map<Boolean, String> tryBind(long qq, String name) {
        Map<Boolean, String> map = new HashMap<>();
        try {
            checkConnection();
            String sql = "SELECT name FROM xxw_players WHERE qq = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, qq);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (resultSet.next()) {
                        String playerName = resultSet.getString("name");
                        String binded = XinxinWhiteList.getInstance().getConfig().getString("messages.binded").replace("%name%", playerName);
                        map.put(false, binded);
                        return map;
                    }
                }
                //定制内容 如果被封禁qq就返回失败
                if (checkIsBaned(qq)) {
                    map.put(false, "你的qq被列入了黑名单，可找群主申请解封。");
                    return map;
                }
                //定制内容 如果被封禁qq就返回失败
                addPlayerData(name.toLowerCase(), qq);
                String bind = XinxinWhiteList.getInstance().getConfig().getString("messages.bind");
                map.put(true, bind);
                BotBind.setBind(String.valueOf(qq), name);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return map;
    }

    @Override
    public boolean checkIsBaned(long qq) {
        try {
            checkConnection();
            String sql = "SELECT * FROM xxw_blackplayers WHERE qq = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, qq);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean banQQ(long qq) {
        try {
            checkConnection();
            String sql = "INSERT INTO xxw_blackplayers (qq) values (?)";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, qq);
                int i = preparedStatement.executeUpdate();
                return i > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public boolean unbanQQ(long qq) {
        try {
            checkConnection();
            String sql = "DELETE FROM xxw_blackplayers WHERE qq = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                preparedStatement.setLong(1, qq);
                int rowsAffected = preparedStatement.executeUpdate();
                return rowsAffected > 0;  // 如果删除了记录，则返回 true
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;  // 删除失败时返回 false
    }

}
