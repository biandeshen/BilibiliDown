package nicelee.bilibili.parsers.impl;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
			currentOffset = "";
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
			String json = util.getContent(url, headers, HttpCookies.globalCookiesWithFingerprint());
			Logger.println(url);
			Logger.println(json);

			JSONObject response = new JSONObject(json);
			if (response.optInt("code") != 0) {
				Logger.println("动态API返回错误: " + response.optString("message"));
				currentOffset = null;
				return pageQueryResult;
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
				if (!DynamicsDB.contains(spaceID, dynamicId))
					recordDynamicToDB(spaceID, dynamicId, type, mods);
				else videoKnown++;
				if (!"DYNAMIC_TYPE_AV".equals(type)) continue;
				videoOnPage++;

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

					// 获取视频详细信息
					VideoInfo video = getAVDetail(bvid, videoFormat, getVideoLink);

					// 设置UP主信息(第一次)
					if (pageQueryResult.getVideoName() == null) {
						JSONObject moduleAuthor = modules.getJSONObject("module_author");
						pageQueryResult.setVideoId(spaceID);
						pageQueryResult.setAuthor(moduleAuthor.getString("name"));
						pageQueryResult.setAuthorId(spaceID);
						pageQueryResult.setVideoName(pageQueryResult.getAuthor() + " - videos");
						pageQueryResult.setBrief("videos - " + paramSetter.getPage());
					}

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
			}
			// early stop: if all video items on this page are in catalog, stop pagination

			// 一页处理完后sleep一次，避免API请求过于密集
			if (map.size() > 0) {
				Thread.sleep(500);
			}

			// 存储下一页的offset
			if (hasMore && nextOffset != null && !nextOffset.isEmpty()) {
				currentOffset = nextOffset;
			} else {
				currentOffset = null;
				if (!isInitialDone) {
					DynamicsDB.markInitialScanDone(spaceID, pageQueryResult.getAuthor());
					Logger.println("UP " + spaceID + " full scan complete");
				}
			}

		} catch (Exception e) {
			Logger.println("获取动态列表失败: " + e.getMessage());
			e.printStackTrace();
			currentOffset = null;
		}

		return pageQueryResult;
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
}
