package nicelee.ui.thread;

import nicelee.bilibili.INeedAV;
import nicelee.bilibili.enums.VideoQualityEnum;
import nicelee.bilibili.exceptions.BilibiliError;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;
import nicelee.bilibili.util.Logger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RealTimeDownloadThread extends Thread {

	List<String> configFilePaths;
	private volatile boolean paused = false;
	private Map<String, Long> configLastModified = new HashMap<>();

	public RealTimeDownloadThread(List<String> configFiles) {
		configFilePaths = new ArrayList<>();
		this.setName("Thread-RealTimeDownload");
		for (String configFile : configFiles) {
			String configFilePath = "config/" + configFile;
			configFilePaths.add(configFilePath);
		}
	}

	final Pattern pagePattern = Pattern.compile("p=[0-9]+$");

	@Override
	public void run() {
		while (!Thread.currentThread().isInterrupted()) {  // 添加无限循环
			try {
				while (paused && !Thread.currentThread().isInterrupted()) {
					try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
					}
										java.util.Collections.shuffle(configFilePaths);
for (String configFilePath : configFilePaths) {
					Logger.println("实时下载进行中");
					File f = ResourcesUtil.search(configFilePath);
					checkValid(f);
					List<BatchDownload> bds = new BatchDownloadsBuilder(new FileInputStream(f)).Build();
					BatchDownload.replaceVideoWithDynamic(bds);
					Logger.println("实时下载进行中。。。。。");
					Logger.println(bds);
					for (BatchDownload batch : bds) {
						Logger.printf("[url:%s] 任务开始", batch.getUrl());
						BatchDownload.processBatchEntry(batch);
						Logger.printf("[url:%s] 任务完毕", batch.getUrl());
					}
				}
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
				e.printStackTrace();
				// 出错后也等待一段时间再继续
				try {
					Thread.sleep(Global.sleepBetweenCycles);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		Logger.println("实时下载运行完毕");
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

	public void addTask(ClipInfo clip) {
	}

	public void showMessageDialog(Component parentComponent, String message, String title, int messageType) throws HeadlessException {
		JOptionPane.showMessageDialog(parentComponent, message, title, messageType);
	}
}
