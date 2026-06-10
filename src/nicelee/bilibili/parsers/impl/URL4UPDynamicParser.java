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
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.HttpHeaders;
import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.RepoUtil;

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
				url += API.genDmImgParams();
				url = API.encWbi(url);
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

			LinkedHashMap<Long, ClipInfo> map = pageQueryResult.getClips();
			int clipIndex = (page - 1) * API_PMAX;
			int skippedCount = 0;

			for (int i = 0; i < items.length(); i++) {
				JSONObject item = items.getJSONObject(i);
				String type = item.optString("type", "");

				if (!"DYNAMIC_TYPE_AV".equals(type)) {
					continue;
				}

				try {
					JSONObject modules = item.getJSONObject("modules");
					JSONObject major = modules.getJSONObject("module_dynamic")
							.getJSONObject("major");

					if (!major.has("archive")) {
						continue;
					}

					JSONObject archive = major.getJSONObject("archive");
					String bvid = archive.getString("bvid");

					// 已入库则跳过，避免浪费API请求
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
						pageQueryResult.setVideoName(pageQueryResult.getAuthor() + "的动态视频");
						pageQueryResult.setBrief("动态视频列表 - " + paramSetter.getPage());
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

			if (skippedCount > 0) {
				Logger.println("本页跳过 " + skippedCount + " 个已入库视频");
			}

			// 一页处理完后sleep一次，避免API请求过于密集
			if (map.size() > 0) {
				Thread.sleep(500);
			}

			// 存储下一页的offset
			if (hasMore && nextOffset != null && !nextOffset.isEmpty()) {
				currentOffset = nextOffset;
			} else {
				currentOffset = null;
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
}
