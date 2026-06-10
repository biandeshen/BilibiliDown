package nicelee.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import nicelee.ui.item.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;

import nicelee.bilibili.INeedLogin;
import nicelee.bilibili.PackageScanLoader;
import nicelee.bilibili.util.CmdUtil;
import nicelee.bilibili.util.ConfigUtil;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.RepoUtil;
import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.ResourcesUtil;
import nicelee.bilibili.util.SysUtil;
import nicelee.ui.item.MJTitleBar;
import nicelee.ui.thread.BatchDownloadRbyRThread;
import nicelee.ui.thread.CookieRefreshThread;
import nicelee.ui.thread.LoginThread;
import nicelee.ui.thread.MonitoringThread;
import nicelee.ui.thread.DownloadRunnable;
import nicelee.bilibili.INeedAV;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;

public class FrameMain extends JFrame {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	JTabbedPane jTabbedpane;// 存放选项卡的组件
	MJTitleBar titleBar;// 标题栏组件

	public static void main(String[] args) {
		System.out.println();
		// System.getProperties().setProperty("file.encoding", "utf-8");
		boolean isFFmpegSupported = SysUtil.surportFFmpegOfficially();
		System.out.println("Java version:" + System.getProperty("java.specification.version"));
		System.out.println(ResourcesUtil.baseDirectory());
		// 读取配置文件
		ConfigUtil.initConfigs();
		// -v 打印版本，然后退出
		if(args.length == 1 && "-v".equalsIgnoreCase(args[0])) {
			System.out.println(Global.version);
			System.exit(0);
		}
		// 初始化 - 检查对数据文件夹是否有“写”的权限
		InitCheck.checkFileAccess();
		// 显示过渡动画
		Global.frWaiting = new FrameWaiting();
		Global.frWaiting.start();

		if (Global.lockCheck) {
			if (ConfigUtil.isRunning()) {
				Global.frWaiting.stop();
				JOptionPane.showMessageDialog(null, "程序已经在运行!", "请注意!!", javax.swing.JOptionPane.WARNING_MESSAGE);
				return;
			}
			ConfigUtil.createLock();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				ConfigUtil.deleteLock();
			}));
		}
		
		nicelee.bilibili.util.custom.System.init(Global.syncServerTime);
//		// 如果存在hosts文件，那么使之生效
//		if (HostSetUtil.readHostsFromFile("config/hosts.config")) {
//			HostSetUtil.injectHosts();
//		}
		// 初始化主题
		initUITheme();
		// 初始化UI
		FrameMain main = new FrameMain();
		main.InitUI();

		// 初始化监控线程，用于刷新下载面板
		MonitoringThread th = new MonitoringThread();
		th.start();

		// 尝试刷新cookie
		INeedLogin inl = new INeedLogin();
		String cookiesStr = inl.readCookies();
		if (cookiesStr != null) {
			Global.needToLogin = true;
			if(Global.tryRefreshCookieOnStartup && !Global.runWASMinBrowser) {
				HttpCookies.setGlobalCookies(HttpCookies.convertCookies(cookiesStr));
				CookieRefreshThread.showTips = false;
				CookieRefreshThread thCR = CookieRefreshThread.newInstance();
				thCR.start();
				try {
					thCR.join();
				} catch (InterruptedException e1) {
				}
				CookieRefreshThread.showTips = true;
			}
		}
		// 初始化 - 登录
		LoginThread loginTh = new LoginThread();
		loginTh.start();

		// 初始化 - ffmpeg环境判断
		InitCheck.checkFFmpeg(isFFmpegSupported);

		//
		if (Global.saveToRepo) {
			RepoUtil.init(false);
		}
		// scan for incomplete .part files
		scanPartFilesAndResume();
//		FrameQRCode qr = new FrameQRCode("https://www.bilibili.com/");
//		qr.initUI();
//		qr.dispose();
		// 预扫描加载类
		PackageScanLoader.validParserClasses.isEmpty();
		if(Global.batchDownloadRbyRRunOnStartup) {
			// 开始按计划周期性批量下载
			new Thread(new Runnable() {
				@Override
				public void run() {
					// 等待相关线程运行完毕
					try {
						loginTh.join();
					} catch (InterruptedException e) {}
					new BatchDownloadRbyRThread(Global.batchDownloadConfigName).start();
				}
			}).start();
		}
		System.out.println("如果过度界面显示时间过长，可双击跳过");
		try {
			while (Global.frWaiting.isVisible()) {
				Thread.sleep(1000);
			}
		} catch (InterruptedException e) {
			Global.frWaiting.stop();
		}
		Global.frWaiting = null;
		main.setVisible(true);
		main.setExtendedState(JFrame.NORMAL);
		main.toFront();
	}

	/**
	 * 
	 */
	static void initUITheme() {
		try {
			if (!Global.themeDefault) {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				Font font = new Font("Dialog", Font.PLAIN, 12);
				Enumeration<Object> keys = UIManager.getDefaults().keys();
				while (keys.hasMoreElements()) {
					Object key = keys.nextElement();
					Object value = UIManager.get(key);
					if (value instanceof javax.swing.plaf.FontUIResource) {
						UIManager.put(key, font);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 
	 */
	public void InitUI() {

		this.setTitle("BiliBili Down~~" + Global.version);
		this.setSize(1200, 745);
		this.setResizable(true);
		this.setMinimumSize(new Dimension(800, 500));
		this.setLocationRelativeTo(null);
		this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		this.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				int active = Global.downloadTab != null ? Global.downloadTab.activeTask : 0;
				if (active > 0) {
					int r = javax.swing.JOptionPane.showConfirmDialog(FrameMain.this,
						active + " 个下载任务正在进行中。\n确定要退出吗？",
						"确认退出", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
					if (r != javax.swing.JOptionPane.YES_OPTION) return;
				}
				// 保存配置
				try { nicelee.bilibili.util.ConfigUtil.saveConfig(); } catch (Exception ignored) {}
				// 关闭线程池
				if (Global.downLoadThreadPool != null) { Global.downLoadThreadPool.shutdown(); try { Global.downLoadThreadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
				if (Global.queryThreadPool != null) { Global.queryThreadPool.shutdown(); try { Global.queryThreadPool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {} }
				System.exit(0);
			}
		});
		URL iconURL = this.getClass().getResource("/resources/favicon.png");
		ImageIcon icon = new ImageIcon(iconURL);
		this.setIconImage(icon.getImage());

		// pane 作为内容容器
		JPanel pane = new JPanel();
		pane.setBackground(Color.WHITE);
		pane.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.GRAY));
		// 添加标题栏
		titleBar = new MJTitleBar(this, true, true);
		pane.add(titleBar);

		jTabbedpane = new JTabbedPane();
		Global.tabs = jTabbedpane;
		jTabbedpane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		jTabbedpane.setPreferredSize(new Dimension(1194, 706));
		// Index Tab
		Global.index = new TabIndex(jTabbedpane);
		jTabbedpane.addTab("主页", Global.index);
		// 下载页
		Global.downloadTab = new TabDownload();
		jTabbedpane.addTab("下载页", Global.downloadTab);
		// 作品页
//		JLabel label = new JLabel("作品页");
//		TabVideo tab = new TabVideo(label);
//		jTabbedpane.addTab("作品页", tab);
//		jTabbedpane.setTabComponentAt(jTabbedpane.indexOfComponent(tab), label);
//		jTabbedpane.addTab("设置页", new TabSettings(jTabbedpane));
		
		pane.add(jTabbedpane);
		this.setContentPane(pane);
		// 关闭窗口时
		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				super.windowClosing(e);
				CmdUtil.deleteAllInactiveCmdTemp();
			}
		});
//		this.setVisible(true);
		SysTray.buildSysTray(this, icon.getImage());
	}

	@Override
	public void setTitle(String title) {
		super.setTitle(title);
		if (titleBar != null) {
			titleBar.setTitle(title);
		}
	}

	// Scan for .part files on startup and offer to resume or clean up
	private static void scanPartFilesAndResume() {
		File downloadDir = new File(Global.savePath);
		if (!downloadDir.exists()) return;
		java.util.List<File> partFiles = new java.util.ArrayList<>();
		findPartFiles(downloadDir, partFiles);
		if (partFiles.isEmpty()) return;
		
		long totalSize = 0;
		for (File f : partFiles) totalSize += f.length();
		StringBuilder sb = new StringBuilder("Found " + partFiles.size() + " incomplete download(s)\n");
		sb.append("Total: ").append(totalSize / 1048576).append(" MB\n\n");
		int showCnt = Math.min(partFiles.size(), 8);
		for (int i = 0; i < showCnt; i++) {
			String name = partFiles.get(i).getName();
			if (name.endsWith(".part")) name = name.substring(0, name.length() - 5);
			sb.append("  ").append(name).append("\n");
		}
		if (partFiles.size() > 8) sb.append("  ... and " + (partFiles.size() - 8) + " more\n");
		sb.append("\nYes = Resume downloads, No = Delete .part files, Cancel = Keep");
		
		int r = javax.swing.JOptionPane.showConfirmDialog(null, sb.toString(),
			"Incomplete Downloads", javax.swing.JOptionPane.YES_NO_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
		if (r == javax.swing.JOptionPane.YES_OPTION) {
			resumeFromPartFiles(partFiles);
		} else if (r == javax.swing.JOptionPane.NO_OPTION) {
			int deleted = 0;
			for (File f : partFiles) { if (f.delete()) deleted++; }
			Logger.println("Deleted " + deleted + " .part files");
		} else {
			Logger.println("Kept " + partFiles.size() + " .part files for resume");
		}
	}
	
	private static void findPartFiles(File dir, java.util.List<File> result) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) {
				findPartFiles(f, result);
			} else if (f.getName().endsWith(".part")) {
				result.add(f);
			}
		}
	}

	private static void resumeFromPartFiles(java.util.List<File> partFiles) {
		java.util.regex.Pattern avidPattern = java.util.regex.Pattern.compile("^(.+?)-(\\d+)-p(\\d+)\\.");
		java.util.HashSet<String> dedupSet = new java.util.HashSet<>();
		int resumed = 0;
		for (File f : partFiles) {
			String name = f.getName();
			String baseName = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
			java.util.regex.Matcher m = avidPattern.matcher(baseName);
			if (m.find()) {
				final String avid = m.group(1);
				final int qn = Integer.parseInt(m.group(2));
				final int page = Integer.parseInt(m.group(3));
				// Dedup: same avid+page+qn may match multiple part files (e.g. _video.m4s + _audio.m4s)
				String key = avid + "-p" + page + "-" + qn;
				if (!dedupSet.add(key)) continue;
				resumed++;
				Global.queryThreadPool.execute(() -> {
					try {
						INeedAV ina = new INeedAV();
						VideoInfo avInfo = ina.getVideoDetail(avid + " p=" + page, Global.downloadFormat, false);
						if (avInfo == null) return;
						for (ClipInfo clip : avInfo.getClips().values()) {
							if (clip.getPage() == page) {
								Global.queryThreadPool.execute(new DownloadRunnable(avInfo, clip, qn));
								break;
							}
						}
					} catch (Exception e) {
						Logger.println("Failed to resume " + avid + " p" + page + ": " + e.getMessage());
					}
				});
			}
		}
		Logger.println("Resumed " + resumed + " download tasks from .part files");
	}
}