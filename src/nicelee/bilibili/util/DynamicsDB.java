package nicelee.bilibili.util;

import java.io.*;
import java.sql.*;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.model.DynamicItem;

/**
 * H2 嵌入式数据库：UP主动态目录 + 扫描状态
 * 替代 CatalogUtil JSON 方案
 */
public class DynamicsDB {

	private static Connection conn;
	private static boolean dbAvailable = false;

	private static final String DB_PATH;

	static {
		File baseDir = ResourcesUtil.baseDirectory();
		DB_PATH = new File(baseDir, "config/dynamics").getAbsolutePath();
	}

	public static void init() {
		try {
			Class.forName("org.h2.Driver");
			// 清理残留 lock 文件
			new File(DB_PATH + ".lock.db").delete();
			conn = DriverManager.getConnection(
				"jdbc:h2:file:" + DB_PATH + ";TRACE_LEVEL_FILE=0", "sa", "");
			createTables();
			migrateFromJson();
			dbAvailable = true;
			Logger.println("DynamicsDB initialized: " + DB_PATH);
		} catch (Exception e) {
			Logger.println("DynamicsDB init failed: " + e.getMessage());
			dbAvailable = false;
			conn = null;
		}
	}

	public static void shutdown() {
		dbAvailable = false;
		try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	}

	// ===== 建表 =====
	private static void createTables() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute(
				"CREATE TABLE IF NOT EXISTS up_dynamics (" +
				"  dynamic_id VARCHAR(64) NOT NULL," +
				"  uid VARCHAR NOT NULL," +
				"  up_name VARCHAR," +
				"  type VARCHAR NOT NULL," +
				"  bvid VARCHAR," +
				"  title VARCHAR," +
				"  cover VARCHAR," +
				"  pub_timestamp BIGINT," +
				"  description VARCHAR," +
				"  duration_text VARCHAR," +
				"  downloaded INTEGER DEFAULT 0," +
				"  downloaded_at VARCHAR," +
				"  qn INTEGER," +
				"  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
				"  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
				"  PRIMARY KEY (uid, dynamic_id))");
			st.execute("CREATE INDEX IF NOT EXISTS idx_dyn_uid_time ON up_dynamics(uid, pub_timestamp DESC)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_dyn_uid_dl ON up_dynamics(uid, downloaded)");
			st.execute("CREATE INDEX IF NOT EXISTS idx_dyn_bvid ON up_dynamics(bvid)");

			st.execute(
				"CREATE TABLE IF NOT EXISTS up_status (" +
				"  uid VARCHAR PRIMARY KEY," +
				"  up_name VARCHAR," +
				"  initial_scan_done INTEGER DEFAULT 0," +
				"  last_offset VARCHAR," +
				"  last_pub_timestamp BIGINT," +
				"  last_scan_time VARCHAR," +
				"  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
				"  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
		}
	}

	// ===== 扫描状态 =====
	public static synchronized boolean isInitialScanDone(String uid) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT initial_scan_done FROM up_status WHERE uid=?")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			return rs.next() && rs.getInt(1) == 1;
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); return false; }
	}

	public static synchronized void markInitialScanDone(String uid, String upName) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO up_status (uid, up_name, initial_scan_done, updated_at) VALUES (?,?,1,CURRENT_TIMESTAMP)")) {
			ps.setString(1, uid);
			ps.setString(2, upName);
			ps.executeUpdate();
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized String getLastOffset(String uid) {
		if (!dbAvailable) return "";
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT last_offset FROM up_status WHERE uid=?")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			return rs.next() ? rs.getString(1) : "";
		} catch (SQLException e) { return ""; }
	}

	public static synchronized void setLastOffset(String uid, String upName, String offset, long lastPubTimestamp) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO up_status (uid, up_name, last_offset, last_pub_timestamp, last_scan_time, updated_at)" +
				" VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
			ps.setString(1, uid);
			ps.setString(2, upName);
			ps.setString(3, offset);
			ps.setLong(4, lastPubTimestamp);
			ps.executeUpdate();
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	// ===== 查询 =====
	public static synchronized boolean contains(String uid, String dynamicId) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT 1 FROM up_dynamics WHERE uid=? AND dynamic_id=?")) {
			ps.setString(1, uid);
			ps.setString(2, dynamicId);
			return ps.executeQuery().next();
		} catch (SQLException e) { return false; }
	}

	public static synchronized boolean containsBvid(String uid, String bvid) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT 1 FROM up_dynamics WHERE uid=? AND bvid=?")) {
			ps.setString(1, uid);
			ps.setString(2, bvid);
			return ps.executeQuery().next();
		} catch (SQLException e) { return false; }
	}

	public static synchronized Set<String> getKnownBvids(String uid) {
		Set<String> set = new HashSet<>();
		if (!dbAvailable) return set;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT bvid FROM up_dynamics WHERE uid=? AND bvid IS NOT NULL")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) set.add(rs.getString(1));
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); }
		return set;
	}

	// ===== 写入（批量 MERGE INTO） =====
	public static synchronized void insertDynamics(List<DynamicItem> items) {
		if (!dbAvailable || items.isEmpty()) return;
		String sql = "MERGE INTO up_dynamics (dynamic_id, uid, up_name, type, bvid, title, cover," +
			" pub_timestamp, description, duration_text, updated_at)" +
			" VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)";
		try {
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement(sql)) {
				int count = 0;
				for (DynamicItem item : items) {
					ps.setString(1, item.getDynamicId());
					ps.setString(2, item.getUid());
					ps.setString(3, item.getUpName());
					ps.setString(4, item.getType());
					ps.setString(5, item.getBvid());
					ps.setString(6, item.getTitle());
					ps.setString(7, item.getCover());
					if (item.getPubTimestamp() != null) ps.setLong(8, item.getPubTimestamp());
					else ps.setNull(8, Types.BIGINT);
					ps.setString(9, item.getDescription());
					ps.setString(10, item.getDurationText());
					ps.addBatch();
					if (++count % 50 == 0) { ps.executeBatch(); }
				}
				ps.executeBatch();
			}
			conn.commit();
			conn.setAutoCommit(true);
		} catch (SQLException e) { Logger.println("DynamicsDB insert: " + e.getMessage()); }
	}

	// ===== 下载状态 =====
	public static synchronized void markDownloaded(String uid, String bvid, int qn) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE up_dynamics SET downloaded=1, downloaded_at=CURRENT_TIMESTAMP, qn=?, updated_at=CURRENT_TIMESTAMP" +
				" WHERE uid=? AND bvid=?")) {
			ps.setInt(1, qn);
			ps.setString(2, uid);
			ps.setString(3, bvid);
			ps.executeUpdate();
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized void markDownloadFailed(String uid, String bvid) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE up_dynamics SET downloaded=-1, updated_at=CURRENT_TIMESTAMP WHERE uid=? AND bvid=?")) {
			ps.setString(1, uid);
			ps.setString(2, bvid);
			ps.executeUpdate();
		} catch (SQLException e) { Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	// ===== 迁移 =====
	private static void migrateFromJson() {
		File catalogDir = new File(ResourcesUtil.baseDirectory(), "config/catalog");
		if (!catalogDir.exists() || !catalogDir.isDirectory()) return;
		File[] files = catalogDir.listFiles((d, n) -> n.endsWith(".json"));
		if (files == null || files.length == 0) return;

		int migrated = 0;
		for (File f : files) {
			try {
				String uid = f.getName().replace(".json", "");
				StringBuilder sb = new StringBuilder();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "utf-8"))) {
					String line; while ((line = r.readLine()) != null) sb.append(line);
				}
				JSONArray bvids = new JSONObject(sb.toString()).getJSONArray("bvids");
				String sql = "MERGE INTO up_dynamics (dynamic_id, uid, type, bvid, updated_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)";
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					for (int i = 0; i < bvids.length(); i++) {
						String bvid = bvids.getString(i);
						ps.setString(1, uid + "_" + bvid); // 合成唯一ID
						ps.setString(2, uid);
						ps.setString(3, "AV");
						ps.setString(4, bvid);
						ps.addBatch();
					}
					ps.executeBatch();
				}
				// 标记为已全扫
				try (PreparedStatement ps = conn.prepareStatement(
						"MERGE INTO up_status (uid, initial_scan_done, updated_at) VALUES (?,1,CURRENT_TIMESTAMP)")) {
					ps.setString(1, uid);
					ps.executeUpdate();
				}
				f.renameTo(new File(catalogDir, f.getName() + ".migrated"));
				migrated++;
			} catch (Exception e) {
				Logger.println("Migration failed for " + f.getName() + ": " + e.getMessage());
			}
		}
		if (migrated > 0) Logger.println("DynamicsDB: migrated " + migrated + " catalog files");
	}
}
