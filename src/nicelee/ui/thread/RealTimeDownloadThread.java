package nicelee.ui.thread;

import nicelee.bilibili.INeedAV;
import nicelee.bilibili.enums.VideoQualityEnum;
import nicelee.bilibili.exceptions.BilibiliError;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;
import nicelee.bilibili.util.DynamicsDB;
import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.RepoUtil;
import nicelee.bilibili.util.ResourcesUtil;
import nicelee.bilibili.util.batchdownload.BatchDownload;
import nicelee.bilibili.util.batchdownload.BatchDownload.BatchDownloadsBuilder;
import nicelee.ui.Global;
import nicelee.ui.thread.DownloadRunnable;
import nicelee.ui.item.DownloadInfoPanel;
import nicelee.ui.item.JOptionPane;
import nicelee.ui.item.JOptionPaneManager;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.LoggerFactory;

public class RealTimeDownloadThread extends Thread {

	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(RealTimeDownloadThread.class);

	List<String> configFilePaths;
	private volatile boolean paused = false;
	private Map<String, Long> configLastModified = new HashMap<>();
	// A+C方案: 扫描轮次计数器(每完成一轮+1),用于分桶判断
	private int scanRound = 0;
	// A方案: 并行扫描线程池(懒加载,复用)
	private ExecutorService scanPool = null;
	// 用于从 batch.getUrl() 提取 uid 的正则(space.bilibili.com/{uid}/dynamic)
	private static final Pattern UID_PATTERN = Pattern.compile("space\\.bilibili\\.com/([0-9]+)/dynamic");

	public RealTimeDownloadThread(List<String> configFiles) {
		configFilePaths = new ArrayList<>();
		this.setName("Thread-RealTimeDownload");
		for (String configFile : configFiles) {
			String configFilePath = "config/" + configFile;
			configFilePaths.add(configFilePath);
		}
	}

	final Pattern pagePattern = Pattern.compile("p=[0-9]+$");

		public void pauseCycle() { paused = true; }
		public void resumeCycle() { paused = false; }

	@Override
	public void run() {
		// A方案: 初始化并行扫描线程池
		int parallel = Math.max(1, Global.scanParallelSize);
		scanPool = Executors.newFixedThreadPool(parallel);
		logger.info("[并行扫描] 线程池大小: {}", parallel);
		try {
			while (!Thread.currentThread().isInterrupted()) {  // 添加无限循环
				try {
					while (paused && !Thread.currentThread().isInterrupted()) {
						try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
						}
					// 修改5-混合方案: 每轮开始时拉取failed_tasks表,恢复跨会话的失败任务
					recoverFailedTasks();
					// A+C方案: 收集本轮所有该扫描的 batch
					List<BatchDownload> batchesToScan = collectBatchesToScan();
					if (batchesToScan.isEmpty()) {
						logger.info("[分桶扫描] 本轮无 batch 需扫描,轮次 {}", scanRound);
					} else {
						logger.info("[分桶扫描] 本轮待扫描 batch 数: {}, 总配置数: {}, 轮次: {}",
							batchesToScan.size(), configFilePaths.size(), scanRound);
						// A方案: 并行处理所有 batch
						processBatchesInParallel(batchesToScan);
					}
					// C方案: 轮次+1
					scanRound++;
					// 每次完整执行完for循环后等待30分钟
					Logger.println("完成一轮实时下载，等待30分钟后继续...");
					Thread.sleep(Global.sleepBetweenCycles); // 30分钟 = 30*60*1000毫秒
				} catch (BilibiliError e) {
					JOptionPaneManager.alertErrMsgWithNewThread("发生了预料之外的错误", ResourcesUtil.detailsOfException(e));
					// 出错后也等待一段时间再继续
					try {
						Thread.sleep(Global.sleepBetweenCycles);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Exception e) {
				logger.error("异常", e);
				// 出错后也等待一段时间再继续
					try {
						Thread.sleep(Global.sleepBetweenCycles);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		} finally {
			// A方案: 退出时关闭线程池
			if (scanPool != null) {
				scanPool.shutdownNow();
				try { scanPool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
			}
		}
		Logger.println("实时下载运行完毕");
	}

	/**
	 * A+C方案: 收集本轮所有该扫描的 batch
	 * 1. 遍历所有配置文件
	 * 2. 对每个 batch 提取 uid,调用 shouldScanThisRound 过滤
	 * 3. 返回本轮需要扫描的 batch 列表
	 */
	private List<BatchDownload> collectBatchesToScan() {
		List<BatchDownload> batchesToScan = new ArrayList<>();
		java.util.Collections.shuffle(configFilePaths);
		int skippedCount = 0;
		for (String configFilePath : configFilePaths) {
			try {
				Logger.println("实时下载进行中");
				File f = ResourcesUtil.search(configFilePath);
				checkValid(f);
				List<BatchDownload> bds = new BatchDownloadsBuilder(new FileInputStream(f)).Build();
				BatchDownload.replaceVideoWithDynamic(bds);
				Logger.println("实时下载进行中。。。。。");
				Logger.println(bds);
				for (BatchDownload batch : bds) {
					// C方案: 根据分桶级别决定是否扫描
					String uid = extractUid(batch.getUrl());
					if (uid != null && !DynamicsDB.shouldScanThisRound(uid, scanRound)) {
						skippedCount++;
						logger.debug("[分桶跳过] uid={} 轮次={} 本轮跳过", uid, scanRound);
						continue;
					}
					batchesToScan.add(batch);
				}
			} catch (Exception e) {
				logger.error("[配置加载失败] {} - {}", configFilePath, e.getMessage());
			}
		}
		if (skippedCount > 0) {
			logger.info("[分桶跳过] 本轮跳过 {} 个 batch (低频UP)", skippedCount);
		}
		return batchesToScan;
	}

	/**
	 * A方案: 并行处理所有 batch
	 * 每个 batch 提交到 scanPool,等待全部完成
	 */
	private void processBatchesInParallel(List<BatchDownload> batches) {
		List<CompletableFuture<Void>> futures = new ArrayList<>(batches.size());
		for (BatchDownload batch : batches) {
			futures.add(CompletableFuture.runAsync(() -> {
				try {
					Logger.printf("[url:%s] 任务开始", batch.getUrl());
					BatchDownload.processBatchEntry(batch);
					Logger.printf("[url:%s] 任务完毕", batch.getUrl());
				} catch (Exception e) {
					logger.error("[扫描异常] url={} - {}", batch.getUrl(), e.getMessage(), e);
				}
			}, scanPool));
		}
		// 等待全部完成
		try {
			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} catch (Exception e) {
			logger.error("[并行扫描] 等待完成异常: {}", e.getMessage());
		}
	}

	/**
	 * 从 batch URL 提取 uid (如 space.bilibili.com/12345/dynamic -> 12345)
	 * @return uid 字符串,无法提取返回 null
	 */
	private String extractUid(String url) {
		if (url == null) return null;
		Matcher m = UID_PATTERN.matcher(url);
		return m.find() ? m.group(1) : null;
	}


	public void checkValid(File f) throws IOException, URISyntaxException {
		if (f == null || !f.exists()) {
			String docsUrl = "https://nICEnnnnnnnLee.github.io/BilibiliDown/guide/advanced/quick-batch-download";
			String warning = "实时下载配置不存在`" + f.getAbsolutePath() + "`!\r\n请参考配置" + docsUrl;
			Object[] options = {"确认", "前往参考文档"};
			int m = JOptionPane.showOptionDialog(null, warning, "错误", JOptionPane.YES_NO_OPTION,
			                                     JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
			if (m == 1) {
				if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(docsUrl));
				else {
					Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
					Transferable trans = new StringSelection(docsUrl);
					clipboard.setContents(trans, null);
					JOptionPane.showMessageDialog(null, "相关网页链接已复制到剪贴板");
				}
			}
			throw new RuntimeException("配置文件`" + f.getAbsolutePath() + "`不存在");
		}
	}

	/**
	 * 修改5-混合方案: 每轮循环开始时拉取failed_tasks表,
	 * 对未在下载列表中、未在仓库中的任务,重新提交DownloadRunnable让它走完整流程。
	 * DownloadRunnable失败时会自动构造FAIL面板+更新failed_tasks(幂等),让MonitoringThread接管重试。
	 */
	private void recoverFailedTasks() {
		List<DynamicsDB.FailedTaskItem> tasks = DynamicsDB.getRetryableTasks(50);
		if (tasks.isEmpty()) return;
		logger.info("[失败任务恢复] 待重试任务数: {}", tasks.size());
		for (DynamicsDB.FailedTaskItem t : tasks) {
			try {
				// 已在下载列表中则跳过(避免重复入队)
				if (isInDownloadList(t.avid, t.page)) {
					continue;
				}
				// 已下载成功则清理(可能用户手动下载过)
				if (RepoUtil.isBvInRepo(t.avid)) {
					DynamicsDB.removeFailedTask(t.avid, t.page, t.qn);
					continue;
				}
				// 标记重试次数+下次重试时间(指数退避),即使本次提交失败,下次也不会立即惊群
				DynamicsDB.markTaskRetried(t.id, t.retryCount + 1);
				// 构造最小VideoInfo+ClipInfo,提交DownloadRunnable让它走完整流程
				VideoInfo avInfo = new VideoInfo();
				avInfo.setVideoId(t.avid);
				avInfo.setVideoName(t.avid);
				ClipInfo clip = new ClipInfo();
				clip.setAvId(t.avid);
				clip.setAvTitle(t.avid);
				clip.setPage(t.page);
				clip.setRemark(t.page);
				int qn = t.qn > 0 ? t.qn : VideoQualityEnum.getQN(Global.menu_qn);
				logger.info("[失败任务恢复] 重新提交: avid={}, page={}, qn={}, retryCount={}", t.avid, t.page, qn, t.retryCount);
				Global.queryThreadPool.execute(new DownloadRunnable(avInfo, clip, qn, t.entryKey));
			} catch (Exception e) {
				logger.warn("[失败任务恢复] avid={} 恢复异常: {}", t.avid, e.getMessage());
			}
		}
	}

	/**
	 * 检查avid+page是否已在下载列表中
	 */
	private boolean isInDownloadList(String avid, int page) {
		if (avid == null) return false;
		for (DownloadInfoPanel dp : Global.downloadTaskList.keySet()) {
			if (avid.equals(dp.getAvid()) && dp.getClipInfo().getPage() == page) {
				return true;
			}
		}
		return false;
	}

	public void addTask(ClipInfo clip) {
	}

	public void showMessageDialog(Component parentComponent, String message, String title, int messageType) throws HeadlessException {
		JOptionPane.showMessageDialog(parentComponent, message, title, messageType);
	}
}
