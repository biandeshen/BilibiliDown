package nicelee.bilibili.parsers.impl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.annotations.Bilibili;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;
import nicelee.bilibili.model.DynamicItem;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.HttpHeaders;
import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.RepoUtil;
import nicelee.bilibili.util.DynamicsDB;

/**
 * 下载UP主动态中的视频
 * <p>https://space.bilibili.com/{uid}/dynamic</p>
 * <p>API: https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid={uid}&offset=</p>
 */
@Bilibili(name = "URL4UPDynamicParser", weight = 72, ifLoad = "listAll", note = "个人动态中的视频")
public class URL4UPDynamicParser extends AbstractPageQueryParser<VideoInfo> {

	private final static Pattern pattern = Pattern.compile("space\\.bilibili\\.com/([0-9]+)/dynamic");
	private String spaceID;
	private String currentOffset;

	// P0-1修复: 串行化/feed/space动态列表API,避免5路并发触发B站风控(-352/-101/87008)
	// 与playurlSemaphore(1)不同,这里用(2)允许有限并行,兼顾性能与风控
	private static final java.util.concurrent.Semaphore feedSpaceSemaphore = new java.util.concurrent.Semaphore(2);
	// 风控错误码退避配置
	private static final int FEED_MAX_RETRY = 3;
	private static final long[] FEED_BACKOFF_MS = {5000, 15000, 30000}; // 5s/15s/30s

	public URL4UPDynamicParser(Object... obj) {
		super(obj);
	}

	@Override
	public boolean matches(String input) {
		matcher = pattern.matcher(input);
		if (matcher.find()) {
			Logger.println("匹配UP主动态视频 URL4UPDynamicParser");
			spaceID = matcher.group(1);
			return true;
		}
		return false;
	}

	@Override
	public String validStr(String input) {
		return input.trim() + "p=" + paramSetter.getPage();
	}

	@Override
	public VideoInfo result(String input, int videoFormat, boolean getVideoLink) {
		return result(pageSize, paramSetter.getPage(), videoFormat, getVideoLink);
	}

	@Override
	public void initPageQueryParam() {
		API_PMAX = 30;
		pageQueryResult = new VideoInfo();
		pageQueryResult.setClips(new LinkedHashMap<>());
	}

	@Override
	public VideoInfo result(int pageSize, int page, Object... obj) {
		initPageQueryParam();
		int videoFormat = (int) obj[0];
		boolean getVideoLink = (boolean) obj[1];
		boolean isInitialDone = DynamicsDB.isInitialScanDone(spaceID);

		if (page == 1) {
			currentOffset = DynamicsDB.getLastOffset(spaceID);
			if (currentOffset == null) currentOffset = "";
			if (!currentOffset.isEmpty())
				Logger.println("UP " + spaceID + " resuming from saved offset: " + currentOffset);
		}

		// 已被标记为无更多数据
		if (currentOffset == null) {
			return pageQueryResult;
		}

		try {
			String url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
					+ "?host_mid=" + spaceID
					+ (currentOffset.isEmpty() ? "" : "&offset=" + currentOffset)
					+ "&timezone_offset=-480";
			HashMap<String, String> headers = new HttpHeaders().getCommonHeaders("api.bilibili.com");
			headers.put("Referer", "https://space.bilibili.com/" + spaceID + "/dynamic");
			headers.put("Origin", "https://space.bilibili.com/");

			// P0-1修复: feedSpaceSemaphore串行化动态列表API + 风控错误码退避重试
			String json = null;
			JSONObject response = null;
			for (int attempt = 0; attempt <= FEED_MAX_RETRY; attempt++) {
				try {
					feedSpaceSemaphore.acquire();
					try {
						json = util.getContent(url, headers, HttpCookies.globalCookiesWithFingerprint());
						Logger.println(url);
						Logger.println(json);
					} finally {
						feedSpaceSemaphore.release();
					}
					response = new JSONObject(json);
					int code = response.optInt("code", -1);
					if (code == 0) break; // 成功
					// P0-1修复: 风控错误码退避(-352风控/-101未登录/87008频控/-404参数)
					String msg = response.optString("message", "unknown");
					Logger.println("动态API返回错误 code=" + code + " msg=" + msg + " attempt=" + (attempt + 1) + "/" + (FEED_MAX_RETRY + 1));
					if (isRateLimitCode(code) && attempt < FEED_MAX_RETRY) {
						long backoff = FEED_BACKOFF_MS[attempt] + ThreadLocalRandom.current().nextLong(0, 3000); // 加jitter避免惊群
						Logger.println("触发风控,退避 " + backoff + "ms 后重试");
						Thread.sleep(backoff);
						continue;
					}
					// 非风控错误或重试耗尽,放弃本轮
					currentOffset = null;
					pageQueryResult.setErrorFlag(true);
					return pageQueryResult;
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					currentOffset = null;
					pageQueryResult.setErrorFlag(true);
					return pageQueryResult;
				}
			}

			JSONObject data = response.getJSONObject("data");
			JSONArray items = data.getJSONArray("items");
			String nextOffset = data.optString("offset", "");
			boolean hasMore = data.optBoolean("has_more", false);
			pageQueryResult.setHasMorePages(hasMore);

			LinkedHashMap<Long, ClipInfo> map = pageQueryResult.getClips();
			int clipIndex = (page - 1) * API_PMAX;
			int skippedCount = 0;
			int videoOnPage = 0;
			int videoKnown = 0;

			for (int i = 0; i < items.length(); i++) {
				JSONObject item = items.getJSONObject(i);
				String type = item.optString("type", "");

				String dynamicId = item.optString("id_str", "");
				JSONObject mods = null;
				try { mods = item.optJSONObject("modules"); } catch (Exception e) {}
				boolean isKnown = DynamicsDB.contains(spaceID, dynamicId);
				if (!isKnown)
					recordDynamicToDB(spaceID, dynamicId, type, mods);
				if (pageQueryResult.getVideoName() == null && mods != null)
					trySetAuthorInfo(mods);
				if (!"DYNAMIC_TYPE_AV".equals(type)) continue;
				videoOnPage++;
				if (isKnown) videoKnown++;

				try {
					JSONObject modules = item.getJSONObject("modules");
					JSONObject major = modules.getJSONObject("module_dynamic")
							.getJSONObject("major");
					if (!major.has("archive")) {
						continue;
					}

					JSONObject archive = major.getJSONObject("archive");
					String bvid = archive.optString("bvid", "");
					if (bvid.isEmpty()) {
						continue;
					}
					// check catalog first, then repo，避免浪费API请求
				if (RepoUtil.isBvInRepo(bvid)) {
					skippedCount++;
					continue;
				}

				// 修改3: 增量模式下,isKnown=true的视频跳过getAVDetail(性能优化)
				// 注意: 若视频下载失败,依赖修改5的failed_tasks队列恢复
				if (isInitialDone && isKnown) {
					continue;
				}

				// 获取视频详细信息
			VideoInfo video = getAVDetail(bvid, videoFormat, getVideoLink);
				// -404等异常返回null,跳过该视频
				if (video == null) {
					Logger.println("跳过视频: " + bvid + " (详情获取失败,可能已删除)");
					continue;
				}
				// 确保UP主信息已设置（一般已在 trySetAuthorInfo 中设置，此处为保险）
					ensureAuthorSet(modules);

					// 将视频的clips加入结果
					for (ClipInfo clip : video.getClips().values()) {
						clip.setListName(pageQueryResult.getVideoName().replaceAll("[/\\\\]", "_"));
						clip.setListOwnerName(pageQueryResult.getAuthor().replaceAll("[/\\\\]", "_"));
						clip.setListOwnerId(pageQueryResult.getAuthorId());
						clip.setRemark(clipIndex++);
						map.put(clip.getcId(), clip);
					}

					if (pageQueryResult.getVideoPreview() == null && archive.has("cover")) {
						pageQueryResult.setVideoPreview(archive.getString("cover"));
					}

				} catch (Exception e) {
					Logger.println("处理动态视频条目失败: " + e.getMessage());
					e.printStackTrace();
				}
			}

			if (skippedCount > 0) Logger.println("本页跳过 " + skippedCount + " 个已入库视频");
			if (isInitialDone && videoOnPage > 0 && videoKnown >= videoOnPage) {
			Logger.println("all videos on page known, stop pagination");
			hasMore = false;
			pageQueryResult.setHasMorePages(false);
		}
			// early stop: if all video items on this page are in catalog, stop pagination

			// 一页处理完后sleep一次，避免API请求过于密集
			// P0-5修复: 加jitter避免5路并发齐步走触发风控
			if (map.size() > 0) {
				long sleepMs = 500 + ThreadLocalRandom.current().nextLong(0, 500); // 500-1000ms随机
				Thread.sleep(sleepMs);
			}

			// 存储下一页的offset
			if (hasMore && nextOffset != null && !nextOffset.isEmpty()) {
				currentOffset = nextOffset;
				if (pageQueryResult.getAuthor() != null)
					DynamicsDB.setLastOffset(spaceID, pageQueryResult.getAuthor(), nextOffset, 0);
			} else {
				currentOffset = null;
				DynamicsDB.setLastOffset(spaceID, pageQueryResult.getAuthor(), "", 0);
				if (!isInitialDone) {
					DynamicsDB.markInitialScanDone(spaceID, pageQueryResult.getAuthor());
					Logger.println("UP " + spaceID + " full scan complete");
				}
			}

		} catch (Exception e) {
			Logger.println("获取动态列表失败: " + e.getMessage());
			e.printStackTrace();
			currentOffset = null;
			pageQueryResult.setErrorFlag(true);
		}

		return pageQueryResult;
	}

	/**
	 * P0-1修复: 判断是否为风控/频控错误码,需要退避重试
	 * -352: 账号风控, -101: 未登录/Cookie失效, 87008: playurl频控,
	 * -403: 权限限制, -412: 风控限制, -509: 请求过于频繁
	 */
	private static boolean isRateLimitCode(int code) {
		return code == -352 || code == -101 || code == 87008
			|| code == -403 || code == -412 || code == -509;
	}

	@Override
	protected boolean query(int page, int min, int max, Object... obj) {
		return false; // 由覆写的 result() 处理
	}
	// Build DynamicItem from modules and insert into DB
	private void recordDynamicToDB(String uid, String dynamicId, String type, JSONObject modules) {
		try {
			DynamicItem di = new DynamicItem();
			di.setDynamicId(dynamicId);
			di.setUid(uid);
			di.setType(type);
			if (modules != null) {
				try {
					JSONObject author = modules.optJSONObject("module_author");
					if (author != null) {
						di.setUpName(author.optString("name"));
						di.setPubTimestamp(author.optLong("pub_ts") * 1000);
					}
				} catch (Exception e) {}
				// 按类型提取信息
				try {
					JSONObject major = modules.optJSONObject("module_dynamic");
					if (major != null) major = major.optJSONObject("major");
					if (major != null) {
						if ("DYNAMIC_TYPE_AV".equals(type) && major.has("archive")) {
							JSONObject a = major.getJSONObject("archive");
							di.setBvid(a.optString("bvid"));
							di.setTitle(a.optString("title"));
							di.setCover(a.optString("cover"));
							di.setDurationText(a.optString("duration_text"));
						} else if ("DYNAMIC_TYPE_DRAW".equals(type) && major.has("draw")) {
							JSONObject d = major.getJSONObject("draw");
							di.setTitle(d.optString("title"));
							di.setDescription(d.optString("desc"));
							di.setCover(d.optJSONArray("items") != null && d.optJSONArray("items").length() > 0 ? d.optJSONArray("items").optJSONObject(0).optString("src") : null);
						} else if ("DYNAMIC_TYPE_WORD".equals(type)) {
							di.setTitle(major.optString("title"));
							di.setDescription(major.optString("desc"));
						} else if ("DYNAMIC_TYPE_ARTICLE".equals(type) && major.has("article")) {
							JSONObject art = major.getJSONObject("article");
							di.setTitle(art.optString("title"));
							di.setDescription(art.optString("desc"));
							di.setCover(art.optString("cover"));
						}
					}
				} catch (Exception e) {}
			}
			DynamicsDB.insertDynamics(java.util.Collections.singletonList(di));
		} catch (Exception e) { Logger.println("recordDynamicToDB error: " + e.getMessage()); }
	}

	// 从任意动态的 module_author 提取UP主信息，确保零视频UP主也能被记录
	private void trySetAuthorInfo(JSONObject modules) {
		try {
			JSONObject moduleAuthor = modules.getJSONObject("module_author");
			pageQueryResult.setVideoId(spaceID);
			pageQueryResult.setAuthor(moduleAuthor.getString("name"));
			pageQueryResult.setAuthorId(spaceID);
			pageQueryResult.setVideoName(pageQueryResult.getAuthor() + " - videos");
			pageQueryResult.setBrief("videos - " + paramSetter.getPage());
		} catch (Exception e) {}
	}

	private void ensureAuthorSet(JSONObject modules) {
		if (pageQueryResult.getVideoName() == null)
			trySetAuthorInfo(modules);
	}
}
