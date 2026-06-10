package nicelee.bilibili.util.batchdownload;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.FavList;
import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.ResourcesUtil;
import nicelee.bilibili.INeedAV;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;
import nicelee.bilibili.enums.VideoQualityEnum;
import nicelee.ui.Global;
import nicelee.ui.item.DownloadInfoPanel;
import nicelee.ui.thread.DownloadRunnable;

public class BatchDownload implements Cloneable {

	String type;
	String url;
	String remark;
	List<Condition[]> stopCondition;
	List<Condition[]> downloadCondition;
	boolean includeBoundsBV;
	boolean alertAfterMissionComplete;
	int startPage;

	public static void main(String[] a) throws FileNotFoundException {
		List<BatchDownload> bd = new BatchDownloadsBuilder(
				"D:\\Workspace\\javaweb-springboot\\BilibiliDown\\release\\config\\click-once-download-all.config")
						.Build();
		System.out.println(bd);
	}

	final static Pattern videoUrlPattern = Pattern.compile("space\\.bilibili\\.com/([0-9]+)/video");

	/**
	 * 将 /video/ 类型的URL替换为 /dynamic（动态流包含投稿视频+专属动态视频，一次API覆盖全部）
	 */
	public static void replaceVideoWithDynamic(List<BatchDownload> batches) {
		for (BatchDownload batch : batches) {
			if (!"url".equals(batch.getType()))
				continue;
			Matcher m = videoUrlPattern.matcher(batch.getUrl());
			if (m.find()) {
				String newUrl = batch.getUrl().replaceAll("/video[/]?.*", "/dynamic");
				Logger.println("将 /video 替换为 /dynamic: " + batch.getUrl() + " -> " + newUrl);
				batch.setUrl(newUrl);
			}
		}
	}

	private BatchDownload(String url) {
		this.url = url;
		remark = "";
		includeBoundsBV = false;
		stopCondition = new ArrayList<>();
		downloadCondition = new ArrayList<>();
//		Condition always = new Condition("_", ":", "_");
//		Condition alwaysNot = new Condition("_", "!", "_");
//		stopCondition.add(new Condition[] { alwaysNot });
//		downloadCondition.add(new Condition[] { always });
	}

	// 行与行之间，用或
	public boolean matchStopCondition(ClipInfo clip, int page) {
		for (Condition[] conditions : stopCondition) {
			if (match(conditions, clip, page)) {
				return true;
			}
		}
		return false;
	}

	// 行与行之间，用或
	public boolean matchDownloadCondition(ClipInfo clip, int page) {
		for (Condition[] conditions : downloadCondition) {
			if (match(conditions, clip, page)) {
				return true;
			}
		}
		return false;
	}

	// 同一行之间，用与
	private boolean match(Condition[] conditions, ClipInfo clip, int page) {
		for (Condition condition : conditions) {
			if (!condition.match(clip, page)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[").append(type).append(":").append(url).append("]\n");
		sb.append("stopCondition:");
		for (Condition[] conditions : stopCondition) {
			sb.append("\n[\n\t");
			for (int i = 0; i < conditions.length; i++) {
				sb.append(conditions[i]).append(",");
			}
			sb.append("\n],");
		}
		sb.append("\n");
		sb.append("downloadCondition:");
		for (Condition[] conditions : downloadCondition) {
			sb.append("\n[\n\t");
			for (int i = 0; i < conditions.length; i++) {
				sb.append(conditions[i]).append(",");
			}
			sb.append("\n],");
		}
		sb.append("\n");
		sb.append("includeBoundsBV:").append(includeBoundsBV).append("\n");
		return sb.toString();
	}

	private void addStopCondition(Condition[] c) {
		stopCondition.add(c);
	}

	private void addDownloadCondition(Condition[] c) {
		downloadCondition.add(c);
	}

	public static class BatchDownloadsBuilder {

		InputStream in;

		public BatchDownloadsBuilder(String path) throws FileNotFoundException {
			in = new FileInputStream(path);
		}

		public BatchDownloadsBuilder(InputStream in) {
			this.in = in;
		}

		final static Pattern urlPattern = Pattern.compile("^\\[(url|favorite):(.*)\\]$");
		final static Pattern stopPattern = Pattern.compile("^stop\\.condition *= *(.*)$");
		final static Pattern downloadPattern = Pattern.compile("^download\\.condition *= *(.*)$");
//		final static Pattern expressionPattern = Pattern.compile("^([^:!<>]+)([:!<>])([^:!<>]+)$");
		final static Pattern expressionPattern = Pattern.compile("^([^:!<>]+)([:!<>])(.+)$");
		final static Pattern otherSettingsPattern = Pattern.compile("^([^=]+) *= *(.*)$");

		public List<BatchDownload> Build() {
			List<BatchDownload> list = new ArrayList<BatchDownload>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "utf-8"));) {
				String line = r.readLine();
				BatchDownload current = null;
				HashMap<String, String> settings = new HashMap<>();
				while (line != null) {
					line = line.trim();
					if (!line.startsWith("#")) {
						// 开始处理
						BatchDownload tmp = current;
						current = matchUrlPattern(list, settings, line, current);
						boolean _void = tmp != current || matchStopPattern(line, current)
								|| matchDownloadPattern(line, current)
								|| matchOtherSettingsPattern(settings, line, current);
					}
					line = r.readLine();
				}
				if (current != null) {
					addBatchToList(list, settings, current);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			ResourcesUtil.closeQuietly(in);
			return list;
		}

		/**
		 * <p>
		 * 如果 BatchDownload type为url, 那么直接加入
		 * 如果 BatchDownload type为favorite, 那么解析为url再加入
		 * </p>
		 */
		private void addBatchToList(List<BatchDownload> list, HashMap<String, String> settings, BatchDownload current) {
			// 根据settings map设置其它属性
			String includeBVBound = settings.getOrDefault("stop.bv.bounds", "exclude");
			current.setIncludeBoundsBV("include".equals(includeBVBound));
			
			String alertAfterMissionComplete = settings.getOrDefault("stop.alert", "true");
			current.setAlertAfterMissionComplete("true".equals(alertAfterMissionComplete));
			
			String startPage = settings.getOrDefault("start.page", "1");
			current.setStartPage(Integer.parseInt(startPage));
			// [url:{url}]
			// [favorite:all]
			// [favorite:{收藏夹1},{收藏夹2},... ]
			if ("url".equals(current.getType())) {
				list.add(current);
			} else if ("favorite".equals(current.getType())) {
				// favorite:all
				// favorite:默认收藏夹,收藏夹1,收藏夹2
				HashSet<String> favs = null;
				if (!"all".equals(current.getUrl())) {
					favs = new HashSet<>();
					for (String part : current.getUrl().split(",")) {
						favs.add(part.trim());
					}
				}
				// 为什么i 为2, 因为 0为请选择收藏夹 , 1为稍后再看
				for (int i = 2; i < Global.index.cmbFavList.getItemCount(); i++) {
					FavList favList = (FavList) Global.index.cmbFavList.getItemAt(i);
					if (favs == null || favs.contains(favList.getTitle())) {
						try {
							BatchDownload copy = (BatchDownload) current.clone();
							copy.setRemark(copy.getType() + "-" + favList.getTitle());
							copy.setType("url");
							copy.setUrl(String.format("https://space.bilibili.com/%d/favlist?fid=%d&ftype=create",
									favList.getOwnerId(), favList.getfId()));
							list.add(copy);
						} catch (CloneNotSupportedException e) {
							e.printStackTrace();
						}

					}
				}
			}
		}

		private boolean matchOtherSettingsPattern(HashMap<String, String> settings, String line,
				BatchDownload current) {
			Matcher m = otherSettingsPattern.matcher(line);
			if (m.find()) {
				settings.put(m.group(1).trim(), m.group(2).trim());
				return true;
			} else
				return false;
		}

		private BatchDownload matchUrlPattern(List<BatchDownload> list, HashMap<String, String> settings, String line,
				BatchDownload current) {
			Matcher m = urlPattern.matcher(line);
			if (m.find()) {
				if (current != null)
					addBatchToList(list, settings, current);
				current = new BatchDownload(m.group(2).trim());
				current.setType(m.group(1).trim());
				settings.clear();
			}
			return current;
		}

		private boolean matchStopPattern(String line, BatchDownload current) {
			Matcher m = stopPattern.matcher(line);
			if (m.find()) {
				// page:7,bv:BV1Ra4y177SE
				String[] conditions = m.group(1).split(",");
				Condition[] c = new Condition[conditions.length];
				for (int i = 0; i < conditions.length; i++) {
					String expression = conditions[i].trim();
					Matcher m1 = expressionPattern.matcher(expression);
					if (!m1.find())
						throw new RuntimeException("非法表达式: " + expression);
					c[i] = (new Condition(m1.group(1).trim(), m1.group(2).trim(), m1.group(3).trim()));
				}
				current.addStopCondition(c);
				return true;
			} else
				return false;
		}

		private boolean matchDownloadPattern(String line, BatchDownload current) {
			Matcher m = downloadPattern.matcher(line);
			if (m.find()) {
				// page:7,bv:BV1Ra4y177SE
				String[] conditions = m.group(1).split(",");
				Condition[] c = new Condition[conditions.length];
				for (int i = 0; i < conditions.length; i++) {
					String expression = conditions[i].trim();
					Matcher m1 = expressionPattern.matcher(expression);
					if (!m1.find())
						throw new RuntimeException("非法表达式: " + expression);
					c[i] = (new Condition(m1.group(1).trim(), m1.group(2).trim(), m1.group(3).trim()));
				}
				current.addDownloadCondition(c);
				return true;
			} else
				return false;
		}
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public List<Condition[]> getStopCondition() {
		return stopCondition;
	}

	public void setStopCondition(List<Condition[]> stopCondition) {
		this.stopCondition = stopCondition;
	}

	public List<Condition[]> getDownloadCondition() {
		return downloadCondition;
	}

	public void setDownloadCondition(List<Condition[]> downloadCondition) {
		this.downloadCondition = downloadCondition;
	}

	public boolean isIncludeBoundsBV() {
		return includeBoundsBV;
	}

	public void setIncludeBoundsBV(boolean includeBoundsBV) {
		this.includeBoundsBV = includeBoundsBV;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int getStartPage() {
		return startPage;
	}

	public void setStartPage(int startPage) {
		this.startPage = startPage;
	}

	public boolean isAlertAfterMissionComplete() {
		return alertAfterMissionComplete;
	}

	public void setAlertAfterMissionComplete(boolean alertAfterMissionComplete) {
		this.alertAfterMissionComplete = alertAfterMissionComplete;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	// Shared download loop used by BatchDownloadThread and RealTimeDownloadThread
	public static void processBatchEntry(BatchDownload batch) {
		java.util.HashSet<String> dedupCache = new java.util.HashSet<>();
		final java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile("p=[0-9]+$");
		INeedAV ina = new INeedAV();
		String validStr = ina.getValidID(batch.getUrl());
		java.util.regex.Matcher m = pagePattern.matcher(validStr);
		boolean isPageable = m.find();
		if (isPageable) validStr = validStr.replaceFirst("p=[0-9]+$", "");
		int page = batch.getStartPage();
		boolean stopFlag = false;
		while (!stopFlag) {
			if (!isPageable && page >= 2) break;
			String sp = validStr + " p=" + page;
			try {
				VideoInfo avInfo = ina.getVideoDetail(sp, Global.downloadFormat, false);
				java.util.Collection<ClipInfo> clips = avInfo.getClips().values();
				if (clips.size() == 0) break;
				for (ClipInfo clip : clips) {
					if (batch.matchStopCondition(clip, page)) {
						if (batch.isIncludeBoundsBV() && batch.matchDownloadCondition(clip, page)) {
							String dedupKey = clip.getAvId() + "-p" + clip.getPage();
							if (dedupCache.add(dedupKey) && !existsInDownloadList(clip)) {
								Global.queryThreadPool.execute(new DownloadRunnable(avInfo, clip, VideoQualityEnum.getQN(Global.menu_qn)));
							}
						}
						stopFlag = true; break;
					}
					if (batch.matchDownloadCondition(clip, page)) {
						String dedupKey = clip.getAvId() + "-p" + clip.getPage();
						if (dedupCache.add(dedupKey) && !existsInDownloadList(clip)) {
							Global.queryThreadPool.execute(new DownloadRunnable(avInfo, clip, VideoQualityEnum.getQN(Global.menu_qn)));
						}
					}
				}
				page++;
				Thread.sleep(Global.sleepBetweenPages);
			} catch (Exception e) { e.printStackTrace(); break; }
		}
		try { Thread.sleep(Global.sleepBetweenBatches); } catch (InterruptedException e) {}
	}

	private static boolean existsInDownloadList(ClipInfo clip) {
		for (DownloadInfoPanel dp : Global.downloadTaskList.keySet()) {
			if (clip.getAvId() != null && clip.getAvId().equals(dp.getAvid()) && dp.getClipInfo().getPage() == clip.getPage()) {
				return true;
			}
		}
		return false;
	}
}