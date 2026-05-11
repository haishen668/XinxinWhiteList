package ltd.dreamcraft.xinxinwhitelist;

import com.xinxin.BotApi.BotBind;
import ltd.dreamcraft.xinxinwhitelist.bstats.Metrics;
import ltd.dreamcraft.xinxinwhitelist.beans.CustomConfig;
import ltd.dreamcraft.xinxinwhitelist.database.MYSQL;
import ltd.dreamcraft.xinxinwhitelist.database.PlayerData;
import ltd.dreamcraft.xinxinwhitelist.database.YAML;
import ltd.dreamcraft.xinxinwhitelist.listeners.onGroup;
import ltd.dreamcraft.xinxinwhitelist.listeners.onJoin;
import ltd.dreamcraft.xinxinwhitelist.listeners.onLogin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

public class XinxinWhiteList extends JavaPlugin {
  private static XinxinWhiteList instance;

  private static CustomConfig mysqlSettings;
  private static Map<String, Long> playerDataCache = new HashMap<>();
  private static List<Runnable> ioTasks = new ArrayList<>();

  public static XinxinWhiteList getInstance() {
    return instance;
  }

  public static PlayerData playerData;

  public static PlayerData getPlayerData() {
    return playerData;
  }

  public static void setPlayerData(PlayerData playerData) {
    XinxinWhiteList.playerData = playerData;
  }

  public static CustomConfig getMysqlSettings() {
    return mysqlSettings;
  }

  @Override
  public void onDisable() {
    for (String name : onGroup.playersMap) {
      onGroup.removePlayer(name);
    }
    // 批量保存缓存中的数据
//    flushCache();
  }

  @Override
  public void onEnable() {
    instance = this;
    saveDefaultConfig();
    registerEvent(new onJoin());
    registerEvent(new onGroup());
    registerEvent(new onLogin());

    Bukkit.getScheduler().runTaskTimer(this, () -> {
      if (System.currentTimeMillis() - onLogin.last > 2000L) {
        onLogin.attacks.set(0);
      }
    }, 40L, 40L);

    for (Player p : Bukkit.getOnlinePlayers()) {
      if (p.hasPermission("admin")) {
        onLogin.admins.add(p.getUniqueId());
      }
    }
    if ("yaml".equalsIgnoreCase(getConfig().getString("database.type"))) {
      playerData = new YAML();
    } else if ("mysql".equalsIgnoreCase(getConfig().getString("database.type"))) {
      playerData = new MYSQL();
    }

    // bStats 统计
    int pluginId = 31251;
    Metrics metrics = new Metrics(this, pluginId);
  }

  private void registerEvent(Listener l) {
    Bukkit.getServer().getPluginManager().registerEvents(l, this);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
      reloadConfig();
      if ("YAML".equalsIgnoreCase(getConfig().getString("database.type"))) {
        playerData.reload();
      }
      sender.sendMessage("§a[XXW] 配置文件已经重新载入");
      return true;
    }
    if (args.length == 1 && "convertmysql".equalsIgnoreCase(args[0])) {
      File file = new File(getDataFolder(), "BindData.yml");
      YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

      if (!file.exists() || config.getKeys(false).isEmpty()) {
        getLogger().warning("BindData.yml不存在或数据为空.");
        return false;
      }

      Set<String> keys = config.getKeys(false);
      for (String key : keys) {
        String qq = key;
        String name = config.getString(key);
        playerData.addPlayerData(name.toLowerCase(), Long.valueOf(qq));
      }
      getLogger().info("数据导入完成.");
      return true;
    }
    if (args.length == 2 && "check".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        String qq = playerData.getPlayerName(args[1].toLowerCase());
        sender.sendMessage("§a[XXW] 此玩家的QQ为: " + qq);
      });
      return true;
    }
    if (args.length == 1 && "convert".equalsIgnoreCase(args[0])) {
      convertYamlToMysql();
      sender.sendMessage("§a[XXW] 正在将YAML数据转换为MySQL...");
      return true;
    }
    if (args.length == 2 && "qq".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        String player = playerData.getPlayerByQQ(args[1]);
        if (player != null) {
          sender.sendMessage("§a[XXW] 此QQ用户所绑定的玩家为: " + player);
        } else {
          sender.sendMessage("§a[XXW] 此QQ没有申请白名单的记录");
        }
      });
      return true;
    }
    if (args.length == 2 && "bdqq".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          String playerName = BotBind.getBindPlayerName(args[1]);
          sender.sendMessage("§a[XXW] 此QQ所绑定的玩家为: " + Bukkit.getOfflinePlayer(playerName).getName());
        } catch (Exception e) {
          e.printStackTrace();
          sender.sendMessage("§a[XXW] §c发生错误!");
        }
      });
      return true;
    }
    if (args.length == 2 && "bdcheck".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(args[1]);
        if (offlinePlayer.getName() != null) {
          sender.sendMessage("§a[XXW] 此玩家绑定的QQ为: " + BotBind.getBindQQ(offlinePlayer.getName()));
        } else {
          sender.sendMessage("§a[XXW] §c没有这个玩家!");
        }
      });
      return true;
    }
    if (args.length == 3 && "modify".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          String name = args[1].toLowerCase();
          Long newQQ = Long.parseLong(args[2]);
          playerData.modifyPlayerData(name, newQQ);
          sender.sendMessage("§a[XXW] §a玩家" + name + "的QQ已经成功更改为: " + newQQ);
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入数字");
        }
      });
      return true;
    }
    if (args.length == 3 && "forcebind".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          String name = args[1].toLowerCase();
          Long qq = Long.parseLong(args[2]);
          if (playerData.getPlayerByQQ(String.valueOf(qq)) == null && playerData.getPlayerName(name) == null) {
            playerData.addPlayerData(name, qq);
            sender.sendMessage("§a[XXW] §a玩家" + name + "和QQ: " + qq + "已经成功绑定");
          } else {
            sender.sendMessage("§a[XXW] §c此QQ已经绑定玩家或者该玩家已被其他人绑定");
          }
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入数字");
        }
      });
      return true;
    }
    if (args.length == 2 && "delete".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        String name = args[1].toLowerCase();
        if (playerData.removePlayerByID(name)) {
          sender.sendMessage("§a[XXW] §c玩家" + name + "的绑定数据已经成功删除");
        } else {
          sender.sendMessage("§a[XXW] §c玩家" + name + "没有绑定过QQ");
        }
      });
      return true;
    }
    if (args.length == 2 && "deleteByQQ".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          long qq = Long.parseLong(args[1]);
          if (playerData.removePlayerDataByQQ(qq)) {
            sender.sendMessage("§a[XXW] §cQQ" + qq + "绑定的玩家数据已经成功删除");
          } else {
            sender.sendMessage("§a[XXW] §cQQ" + qq + "没有绑定过玩家");
          }
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入有效的QQ号码");
        }
      });
      return true;
    }
    if (args.length == 2 && "checkGroup".equalsIgnoreCase(args[0])) {
      if (getConfig().getBoolean("kick_unbind")) {
        try {
          playerData.removeWhiteListByGroupId(Long.parseLong(args[1]));
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入有效的QQ群号码");
        }
      }
      return true;
    }
    // 添加 ban 命令
    if (args.length == 2 && "ban".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          long qq = Long.parseLong(args[1]);
          if (playerData.banQQ(qq)) {
            sender.sendMessage("§a[XXW] QQ " + qq + " 已被封禁");
          } else {
            sender.sendMessage("§a[XXW] §c封禁失败");
          }
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入有效的QQ号码");
        }
      });
      return true;
    }
    // 添加 unban 命令
    if (args.length == 2 && "unban".equalsIgnoreCase(args[0])) {
      Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
        try {
          long qq = Long.parseLong(args[1]);
          if (playerData.unbanQQ(qq)) {
            sender.sendMessage("§a[XXW] QQ " + qq + " 已被解封");
          } else {
            sender.sendMessage("§a[XXW] §c解封失败");
          }
        } catch (NumberFormatException e) {
          sender.sendMessage("§a[XXW] §c请输入有效的QQ号码");
        }
      });
      return true;
    }
    sender.sendMessage("§a/xxw reload —— 重新载入配置文件");
    sender.sendMessage("§a/xxw check [玩家] —— 查看玩家QQ");
    sender.sendMessage("§a/xxw qq [QQ号码] —— 查看QQ所绑定的玩家");
    sender.sendMessage("§a/xxw bdqq [QQ号码] —— [bot内]查看QQ所绑定的玩家");
    sender.sendMessage("§a/xxw bdcheck [玩家] —— [bot内]查看玩家绑定的QQ");
    sender.sendMessage("§a/xxw modify [玩家] [QQ] —— 更改玩家所绑定的QQ");
    sender.sendMessage("§a/xxw forcebind [玩家] [QQ] —— 手动增加新的玩家和QQ数据");
    sender.sendMessage("§c/xxw delete [玩家] —— 删除玩家绑定数据");
    sender.sendMessage("§c/xxw deleteByQQ [QQ] —— 删除玩家绑定数据");
    sender.sendMessage("§c/xxw convert —— 将YAML数据转换为MySQL");
    sender.sendMessage("§c/xxw ban [QQ] —— 封禁QQ[MYSQL]");
    sender.sendMessage("§c/xxw unban [QQ] —— 解封QQ[MYSQL]");
    sender.sendMessage("§c注意！！！所有涉及绑定解绑白名单的命令均为异步，重载配置为同步命令。");
    return true;
  }
  public void convertYamlToMysql() {
    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
      FileConfiguration playerDataTemp = new CustomConfig("players.yml", XinxinWhiteList.getInstance()).getConfig();
      List<String> playerNames = new ArrayList<>(playerDataTemp.getKeys(false));
      if (!playerNames.isEmpty()) {
        Connection connection = null;
        PreparedStatement pstmt = null;
        try {
          connection = MYSQL.getConnection();
          connection.setAutoCommit(false); // 开启事务
          String sql = "INSERT INTO xxw_players (name, qq) VALUES (?, ?)";
          pstmt = connection.prepareStatement(sql);
          for (String playerName : playerNames) {
            long qqId = playerDataTemp.getLong(playerName.toLowerCase());
            pstmt.setString(1, playerName.toLowerCase());
            pstmt.setLong(2, qqId);
            pstmt.addBatch();
          }
          pstmt.executeBatch(); // 执行批量插入
          connection.commit(); // 提交事务
          getLogger().info("§a[XXW] §cYAML数据已成功转换为MySQL");
        } catch (SQLException e) {
          getLogger().severe("§a[XXW] §cYAML数据转换为MySQL失败: " + e.getMessage());
          if (connection != null) {
            try {
              connection.rollback();
            } catch (SQLException ex) {
              ex.printStackTrace();
            }
          }
        } finally {
          if (pstmt != null) {
            try {
              pstmt.close();
            } catch (SQLException e) {
              e.printStackTrace();
            }
          }
          if (connection != null) {
            try {
              connection.setAutoCommit(true);
            } catch (SQLException e) {
              e.printStackTrace();
            }
          }
        }
      }
    });
  }


//  private void flushCache() {
//    // 批量保存缓存中的数据
//    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
//      for (Map.Entry<String, Long> entry : playerDataCache.entrySet()) {
//        playerData.getConfig().set(entry.getKey(), entry.getValue());
//      }
//      playerData.save();
//    });
//  }

}
