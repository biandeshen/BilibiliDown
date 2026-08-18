
package nicelee.bilibili.util;

import java.io.*;
import java.sql.*;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.model.DynamicItem;

/**
 * H2 嵌入式数据库：UP主动态目录 + 扫描状态 + 大文件待确认队列
 * 替代 CatalogUtil JSON 方案
 */
public class DynamicsDB {

	private static Connection conn;
	private static boolean dbAvailable = false;

	private static final String DB_PATH;

	static {
		String baseDir = ResourcesUtil.baseDirectory();
		DB_PATH = baseDir + "/config/dynamics";
	}

	public static void init() {
		try {
			Class.forName("org.h2.Driver");
			// 清理残留 lock 文件
			new File(DB_PATH + ".lock.db").delete();
			try { org.h2.tools.Server.createWebServer("-webPort", "8082").start();
		org.h2.tools.Server.createTcpServer("-tcpPort", "9092").start(); } catch (Exception e) { Logger.println("H2 WebServer: " + e.getMessage()); }
				conn = DriverManager.getConnection(
				"jdbc:h2:file:" + DB_PATH + ";TRACE_LEVEL_FILE=0;AUTO_SERVER=TRUE", "sa", "");
			createTables();
			dbAvailable = true;
			migrateSchema();
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
st.execute(
				"CREATE TABLE IF NOT EXISTS large_file_queue (" +
				"  id IDENTITY PRIMARY KEY," +
				"  uid VARCHAR," +
				"  up_name VARCHAR," +
				"  bvid VARCHAR," +
				"  avid VARCHAR," +
				"  cid VARCHAR," +
				"  real_qn INTEGER," +
				"  page INTEGER," +
				"  av_title VARCHAR," +
				"  cover VARCHAR," +
				"  estimated_size BIGINT," +
				"  url_query VARCHAR," +
				"  qn INTEGER," +
				"  formatted_title VARCHAR," +
				"  status INTEGER DEFAULT 0," +
				"  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
		st.execute("CREATE INDEX IF NOT EXISTS idx_lfq_status ON large_file_queue(status)");
		st.execute("CREATE INDEX IF NOT EXISTS idx_lfq_uid ON large_file_queue(uid)");

		st.execute(
			"CREATE TABLE IF NOT EXISTS batch_scan_status (" +
			"  entry_key VARCHAR PRIMARY KEY," +
			"  full_scan_done INTEGER DEFAULT 0," +
			"  last_scanned_page INTEGER DEFAULT 0," +
			"  start_page_snapshot INTEGER DEFAULT 0," +
			"  last_scan_time VARCHAR," +
			"  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
			"  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

		// 修改5: 失败任务持久化重试队列
		st.execute(
			"CREATE TABLE IF NOT EXISTS failed_tasks (" +
			"  id IDENTITY PRIMARY KEY," +
			"  entry_key VARCHAR," +
			"  avid VARCHAR NOT NULL," +
			"  bvid VARCHAR," +
			"  page INT," +
			"  qn INT," +
			"  fail_reason VARCHAR," +
			"  retry_count INT DEFAULT 0," +
			"  next_retry_at TIMESTAMP," +
			"  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
			"  UNIQUE(avid, page, qn))");
		st.execute("CREATE INDEX IF NOT EXISTS idx_ft_retry ON failed_tasks(next_retry_at, retry_count)");
		}
	}

	// ===== 失败任务重试队列 =====
	public static synchronized void insertFailedTask(String entryKey, String avid, String bvid, int page, int qn, String failReason) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO failed_tasks (entry_key, avid, bvid, page, qn, fail_reason, retry_count, next_retry_at, updated_at) " +
				"KEY (avid, page, qn) " +
				"VALUES (?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
			ps.setString(1, entryKey);
			ps.setString(2, avid);
			ps.setString(3, bvid);
			ps.setInt(4, page);
			ps.setInt(5, qn);
			ps.setString(6, failReason);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("insertFailedTask: " + e.getMessage()); }
	}

	public static synchronized List<FailedTaskItem> getRetryableTasks(int maxCount) {
		List<FailedTaskItem> list = new ArrayList<>();
		if (!dbAvailable) return list;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT id, entry_key, avid, bvid, page, qn, fail_reason, retry_count FROM failed_tasks " +
				"WHERE next_retry_at IS NULL OR next_retry_at <= CURRENT_TIMESTAMP " +
				"ORDER BY created_at LIMIT ?")) {
			ps.setInt(1, maxCount);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				FailedTaskItem item = new FailedTaskItem();
				item.id = rs.getInt(1);
				item.entryKey = rs.getString(2);
				item.avid = rs.getString(3);
				item.bvid = rs.getString(4);
				item.page = rs.getInt(5);
				item.qn = rs.getInt(6);
				item.failReason = rs.getString(7);
				item.retryCount = rs.getInt(8);
				list.add(item);
			}
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("getRetryableTasks: " + e.getMessage()); }
		return list;
	}

	public static synchronized void markTaskRetried(int id, int retryCount) {
		if (!dbAvailable) return;
		// 指数退避：2^retryCount * 60秒，最大30分钟
		long delaySec = Math.min((1L << retryCount) * 60, 1800);
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE failed_tasks SET retry_count=?, next_retry_at=DATEADD('SECOND', ?, CURRENT_TIMESTAMP) WHERE id=?")) {
			ps.setInt(1, retryCount);
			ps.setLong(2, delaySec);
			ps.setInt(3, id);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("markTaskRetried: " + e.getMessage()); }
	}

	public static synchronized void removeFailedTask(String avid, int page, int qn) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM failed_tasks WHERE avid=? AND page=? AND qn=?")) {
			ps.setString(1, avid);
			ps.setInt(2, page);
			ps.setInt(3, qn);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("removeFailedTask: " + e.getMessage()); }
	}

	public static class FailedTaskItem {
		public int id;
		public String entryKey;
		public String avid;
		public String bvid;
		public int page;
		public int qn;
		public String failReason;
		public int retryCount;
	}

	// ===== 大文件队列 =====
	public static synchronized void insertLargeFile(String uid, String upName, String bvid, String avid, String cid, int realQN, int page,
			String avTitle, String cover, long estimatedSize, String urlQuery, int qn, String formattedTitle) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO large_file_queue (uid, up_name, bvid, avid, cid, real_qn, page, av_title, cover, estimated_size, url_query, qn, formatted_title)" +
				" VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
			ps.setString(1, uid);
			ps.setString(2, upName);
			ps.setString(3, bvid);
			ps.setString(4, avid);
			ps.setString(5, cid);
			ps.setInt(6, realQN);
			ps.setInt(7, page);
			ps.setString(8, avTitle);
			ps.setString(9, cover);
			ps.setLong(10, estimatedSize);
			ps.setString(11, urlQuery);
			ps.setInt(12, qn);
			ps.setString(13, formattedTitle);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized List<LargeFileItem> getPendingLargeFiles() {
		List<LargeFileItem> list = new ArrayList<>();
		if (!dbAvailable) return list;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT id, uid, up_name, bvid, avid, cid, real_qn, page, av_title, cover," +
				" estimated_size, url_query, qn, formatted_title, status" +
				" FROM large_file_queue WHERE status=0 ORDER BY id")) {
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				LargeFileItem item = new LargeFileItem();
				item.id = rs.getInt(1);
				item.uid = rs.getString(2);
				item.upName = rs.getString(3);
				item.bvid = rs.getString(4);
				item.avid = rs.getString(5);
				item.cid = rs.getString(6);
				item.realQN = rs.getInt(7);
				item.page = rs.getInt(8);
				item.avTitle = rs.getString(9);
				item.cover = rs.getString(10);
				item.estimatedSize = rs.getLong(11);
				item.urlQuery = rs.getString(12);
				item.qn = rs.getInt(13);
				item.formattedTitle = rs.getString(14);
				item.status = rs.getInt(15);
				list.add(item);
			}
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
		return list;
	}

	public static synchronized void markLargeFileDone(int id) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE large_file_queue SET status=1 WHERE id=?")) {
			ps.setInt(1, id);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized void markLargeFileIgnored(int id) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE large_file_queue SET status=-1 WHERE id=?")) {
			ps.setInt(1, id);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized int countPendingLargeFiles() {
		if (!dbAvailable) return 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT COUNT(*) FROM large_file_queue WHERE status=0")) {
			ResultSet rs = ps.executeQuery();
			return rs.next() ? rs.getInt(1) : 0;
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return 0; }
	}

	// ===== 扫描状态 =====
	public static synchronized boolean isInitialScanDone(String uid) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT initial_scan_done FROM up_status WHERE uid=?")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			return rs.next() && rs.getInt(1) == 1;
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return false; }
	}

	// ===== 分桶扫描(C方案) =====
	// 桶分级: P0(7天内)P1(30天内)P2(90天内)P3(90天+),未全量扫描完成的UP总是P0
	// 扫描频率: P0每轮 P1每2轮 P2每4轮 P3每8轮
	private static final long SEVEN_DAYS_MS = 7L * 86400 * 1000;
	private static final long THIRTY_DAYS_MS = 30L * 86400 * 1000;
	private static final long NINETY_DAYS_MS = 90L * 86400 * 1000;

	/**
	 * 获取UP最新动态发布时间戳(从up_dynamics表查)
	 * @return 0 表示无数据或查询失败
	 */
	public static synchronized long getLatestPubTimestamp(String uid) {
		if (!dbAvailable || uid == null) return 0;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT MAX(pub_timestamp) FROM up_dynamics WHERE uid=?")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			return rs.next() ? rs.getLong(1) : 0;
		} catch (SQLException e) { checkConnectionValidity(e); return 0; }
	}

	/**
	 * 获取UP分桶级别(0-3)
	 * 0=P0高频(7天内有更新),1=P1中频(30天),2=P2低频(90天),3=P3静默(90天+)
	 * 未完成全量扫描的UP返回0(每轮必扫)
	 */
	public static synchronized int getBucketLevel(String uid) {
		if (!dbAvailable || uid == null) return 0;
		// 未完成全量扫描的UP,每轮必扫
		if (!isInitialScanDone(uid)) return 0;
		long latest = getLatestPubTimestamp(uid);
		if (latest == 0) return 3;  // 无任何动态记录,视为静默
		long delta = System.currentTimeMillis() - latest;
		if (delta <= SEVEN_DAYS_MS) return 0;
		if (delta <= THIRTY_DAYS_MS) return 1;
		if (delta <= NINETY_DAYS_MS) return 2;
		return 3;
	}

	/**
	 * 根据桶级别和当前轮次决定是否扫描该UP
	 * @param uid UP的uid
	 * @param scanRound 当前扫描轮次(从0开始递增)
	 * @return true 表示本轮需要扫描
	 */
	public static synchronized boolean shouldScanThisRound(String uid, int scanRound) {
		int bucket = getBucketLevel(uid);
		switch (bucket) {
			case 0: return true;              // P0 每轮必扫
			case 1: return scanRound % 2 == 0; // P1 每2轮扫一次
			case 2: return scanRound % 4 == 0; // P2 每4轮扫一次
			case 3: return scanRound % 8 == 0; // P3 每8轮扫一次
			default: return true;
		}
	}

	public static synchronized void markInitialScanDone(String uid, String upName) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO up_status (uid, up_name, initial_scan_done, updated_at) VALUES (?,?,1,CURRENT_TIMESTAMP)")) {
			ps.setString(1, uid);
			ps.setString(2, upName);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized String getLastOffset(String uid) {
		if (!dbAvailable) return "";
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT last_offset FROM up_status WHERE uid=?")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			return rs.next() ? rs.getString(1) : "";
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return ""; }
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
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	// ===== 批量下载扫描状态 =====
	public static synchronized boolean isBatchScanDone(String entryKey) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT full_scan_done FROM batch_scan_status WHERE entry_key=?")) {
			ps.setString(1, entryKey);
			ResultSet rs = ps.executeQuery();
			return rs.next() && rs.getInt(1) == 1;
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return false; }
	}

	/**
	 * 获取批量扫描进度。DB 不可用时返回 null，调用方应跳过进度跟踪。
	 * 无记录时返回 fullScanDone=false, lastScannedPage=0, startPageSnapshot=0
	 */
	public static synchronized BatchScanState getBatchScanProgress(String entryKey) {
		if (!dbAvailable) return null;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT full_scan_done, last_scanned_page, start_page_snapshot FROM batch_scan_status WHERE entry_key=?")) {
			ps.setString(1, entryKey);
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				return new BatchScanState(rs.getInt(1) == 1, rs.getInt(2), rs.getInt(3));
			}
			return new BatchScanState(false, 0, 0);
		} catch (SQLException e) {
			Logger.println("DynamicsDB getBatchScanProgress: " + e.getMessage());
			checkConnectionValidity(e);
			return null;
		}
	}

	/**
	 * 更新扫描进度（不标记完成）。在全量扫描每页成功后调用。
	 */
	public static synchronized void updateBatchScanPage(String entryKey, int page, int startPage) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO batch_scan_status (entry_key, full_scan_done, last_scanned_page, start_page_snapshot, last_scan_time, updated_at)" +
				" VALUES (?, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
			ps.setString(1, entryKey);
			ps.setInt(2, page);
			ps.setInt(3, startPage);
			ps.executeUpdate();
		} catch (SQLException e) {
			Logger.println("DynamicsDB updateBatchScanPage: " + e.getMessage());
			checkConnectionValidity(e);
		}
	}

	/**
	 * 重置扫描进度（当 startPage 配置变化时调用）
	 */
	public static synchronized void resetBatchScan(String entryKey, int startPage) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO batch_scan_status (entry_key, full_scan_done, last_scanned_page, start_page_snapshot, last_scan_time, updated_at)" +
				" VALUES (?, 0, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
			ps.setString(1, entryKey);
			ps.setInt(2, startPage);
			ps.executeUpdate();
		} catch (SQLException e) {
			Logger.println("DynamicsDB resetBatchScan: " + e.getMessage());
			checkConnectionValidity(e);
		}
	}

	/**
	 * 标记全量扫描完成，同时清除 last_scanned_page（后续进入增量模式）
	 */
	public static synchronized void markBatchScanDone(String entryKey) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"MERGE INTO batch_scan_status (entry_key, full_scan_done, last_scanned_page, start_page_snapshot, last_scan_time, updated_at)" +
				" VALUES (?, 1, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
			ps.setString(1, entryKey);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	/**
	 * 批量扫描进度状态
	 */
	public static class BatchScanState {
		public final boolean fullScanDone;
		public final int lastScannedPage;
		public final int startPageSnapshot;
		public BatchScanState(boolean fullScanDone, int lastScannedPage, int startPageSnapshot) {
			this.fullScanDone = fullScanDone;
			this.lastScannedPage = lastScannedPage;
			this.startPageSnapshot = startPageSnapshot;
		}
	}

	// ===== 查询 =====
	public static synchronized boolean contains(String uid, String dynamicId) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT 1 FROM up_dynamics WHERE uid=? AND dynamic_id=?")) {
			ps.setString(1, uid);
			ps.setString(2, dynamicId);
			return ps.executeQuery().next();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return false; }
	}

	public static synchronized boolean containsBvid(String uid, String bvid) {
		if (!dbAvailable) return false;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT 1 FROM up_dynamics WHERE uid=? AND bvid=?")) {
			ps.setString(1, uid);
			ps.setString(2, bvid);
			return ps.executeQuery().next();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); return false; }
	}

	public static synchronized Set<String> getKnownBvids(String uid) {
		Set<String> set = new HashSet<>();
		if (!dbAvailable) return set;
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT bvid FROM up_dynamics WHERE uid=? AND bvid IS NOT NULL")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) set.add(rs.getString(1));
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
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
		} catch (SQLException e) {
			checkConnectionValidity(e);
			try { conn.rollback(); } catch (SQLException ignored) {}
			Logger.println("DynamicsDB insert: " + e.getMessage());
		} finally {
			try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
		}
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
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	public static synchronized void markDownloadFailed(String uid, String bvid) {
		if (!dbAvailable) return;
		try (PreparedStatement ps = conn.prepareStatement(
				"UPDATE up_dynamics SET downloaded=-1, updated_at=CURRENT_TIMESTAMP WHERE uid=? AND bvid=?")) {
			ps.setString(1, uid);
			ps.setString(2, bvid);
			ps.executeUpdate();
		} catch (SQLException e) { checkConnectionValidity(e); Logger.println("DynamicsDB: " + e.getMessage()); }
	}

	// ===== 迁移 =====

	/**
	 * 幂等迁移：为已有 batch_scan_status 表补充新字段（兼容旧版本数据库）
	 */
	private static void migrateSchema() {
		if (!dbAvailable) return;
		try (Statement stmt = conn.createStatement()) {
			// 检查并添加 batch_scan_status.last_scanned_page
			if (!columnExists(stmt, "BATCH_SCAN_STATUS", "LAST_SCANNED_PAGE")) {
				stmt.execute("ALTER TABLE batch_scan_status ADD COLUMN last_scanned_page INT DEFAULT 0");
				Logger.println("Schema迁移: 添加 batch_scan_status.last_scanned_page");
			}
			// 检查并添加 batch_scan_status.start_page_snapshot
			if (!columnExists(stmt, "BATCH_SCAN_STATUS", "START_PAGE_SNAPSHOT")) {
				stmt.execute("ALTER TABLE batch_scan_status ADD COLUMN start_page_snapshot INT DEFAULT 0");
				Logger.println("Schema迁移: 添加 batch_scan_status.start_page_snapshot");
			}
			// 检查并添加 large_file_queue.avid
			if (!columnExists(stmt, "LARGE_FILE_QUEUE", "AVID")) {
				stmt.execute("ALTER TABLE large_file_queue ADD COLUMN avid VARCHAR");
				Logger.println("Schema迁移: 添加 large_file_queue.avid");
			}
			// 检查并添加 large_file_queue.cid
			if (!columnExists(stmt, "LARGE_FILE_QUEUE", "CID")) {
				stmt.execute("ALTER TABLE large_file_queue ADD COLUMN cid VARCHAR");
				Logger.println("Schema迁移: 添加 large_file_queue.cid");
			}
			// 检查并添加 large_file_queue.real_qn
			if (!columnExists(stmt, "LARGE_FILE_QUEUE", "REAL_QN")) {
				stmt.execute("ALTER TABLE large_file_queue ADD COLUMN real_qn INT");
				Logger.println("Schema迁移: 添加 large_file_queue.real_qn");
			}
			// 检查并添加 large_file_queue.page
			if (!columnExists(stmt, "LARGE_FILE_QUEUE", "PAGE")) {
				stmt.execute("ALTER TABLE large_file_queue ADD COLUMN page INT");
				Logger.println("Schema迁移: 添加 large_file_queue.page");
			}
		} catch (SQLException e) {
			Logger.println("Schema迁移失败(可忽略): " + e.getMessage());
		}
	}

	/**
	 * 判断指定表的列是否存在（幂等迁移辅助）
	 */
	private static boolean columnExists(Statement stmt, String tableName, String columnName) throws SQLException {
		try (ResultSet rs = stmt.executeQuery(
				"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
				"WHERE TABLE_NAME='" + tableName + "' AND COLUMN_NAME='" + columnName + "'")) {
			return rs.next();
		}
	}

	/**
	 * 判断 SQLException 是否为连接级错误（SQLState 08xxx）
	 * 仅连接级错误才禁用数据库并触发重连；SQL 逻辑错误不改变 dbAvailable
	 */
	private static void checkConnectionValidity(SQLException e) {
		if (e == null) return;
		String sqlState = e.getSQLState();
		if (sqlState != null && sqlState.startsWith("08")) {
			Logger.println("DynamicsDB: 连接异常 SQLState=" + sqlState + "，尝试重连...");
			reconnectWithBackoff();
		}
		// 非08类异常（如约束冲突、语法错误）不改变 dbAvailable
	}

	/**
	 * 指数退避重连：1s/2s/4s/8s/16s，最多5次
	 */
	private static void reconnectWithBackoff() {
		long[] delays = { 1000, 2000, 4000, 8000, 16000 };
		for (int i = 0; i < delays.length; i++) {
			try {
				Thread.sleep(delays[i]);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
			try {
				new File(DB_PATH + ".lock.db").delete();
				conn = DriverManager.getConnection(
					"jdbc:h2:file:" + DB_PATH + ";TRACE_LEVEL_FILE=0;AUTO_SERVER=TRUE", "sa", "");
				dbAvailable = true;
				Logger.println("DynamicsDB: 第" + (i + 1) + "次重连成功");
				return;
			} catch (Exception ex) {
				Logger.println("DynamicsDB: 第" + (i + 1) + "次重连失败: " + ex.getMessage());
			}
		}
		dbAvailable = false;
		Logger.println("DynamicsDB: 重连全部失败，禁用数据库");
	}


	// ===== 大文件待确认项 =====
	public static class LargeFileItem {
		public int id;
		public String uid, upName, bvid, avid, cid, avTitle, cover, urlQuery, formattedTitle;
		public long estimatedSize;
		public int qn, realQN, page, status;
	}

	
}