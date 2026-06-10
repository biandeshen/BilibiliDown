package nicelee.bilibili.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import nicelee.bilibili.API;
import nicelee.ui.Global;

/**
 * 一次性迁移工具：将下载目录按 UP主 UID_Name/ 重新组织
 */
public class MigrateDownloadDir {

	static Pattern urlUidPattern = Pattern.compile("space\\.bilibili\\.com/([0-9]+)/");
	static Pattern configSectionPattern = Pattern.compile("^\\[url:.*\\]$");

	/**
	 * 执行迁移：扫描config获取UID→名字映射，然后重命名下载目录中的UP主文件夹
	 */
	public static String migrate() {
		StringBuilder log = new StringBuilder();
		try {
			File configDir = new File(ResourcesUtil.baseDirectory(), "config");
			File downloadDir = new File(Global.savePath);

			if (!downloadDir.exists() || !downloadDir.isDirectory()) {
				return "下载目录不存在: " + downloadDir.getAbsolutePath();
			}

			// 1. 从所有batch config中提取唯一UID
			HashSet<String> uids = new HashSet<>();
			File[] configFiles = configDir.listFiles((dir, name) -> name.startsWith("batchDownload") && name.endsWith(".config"));
			if (configFiles != null) {
				for (File cf : configFiles) {
					try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(cf), "utf-8"))) {
						String line;
						while ((line = r.readLine()) != null) {
							Matcher m = urlUidPattern.matcher(line);
							if (m.find()) {
								uids.add(m.group(1));
							}
						}
					} catch (Exception e) {
						log.append("读取config失败: ").append(cf.getName()).append("\n");
					}
				}
			}
			log.append("找到 ").append(uids.size()).append(" 个唯一UID\n");

			// 2. 通过API获取每个UID的显示名
			HashMap<String, String> uidToName = new HashMap<>();
			HashMap<String, String> nameToUid = new HashMap<>();
			HttpRequestUtil util = new HttpRequestUtil();
			HttpHeaders headers = new HttpHeaders();

			int idx = 0;
			for (String uid : uids) {
				try {
					String url = "https://api.bilibili.com/x/space/acc/info?mid=" + uid;
					url += API.genDmImgParams();
					url = API.encWbi(url);
					String json = util.getContent(url, headers.getCommonHeaders("api.bilibili.com"),
							HttpCookies.globalCookiesWithFingerprint());
					JSONObject data = new JSONObject(json).getJSONObject("data");
					String name = data.getString("name");
					uidToName.put(uid, name);
					// name可能重复(不同UID同名)，保留第一个
					nameToUid.putIfAbsent(name, uid);
					log.append("  [").append(++idx).append("/").append(uids.size())
						.append("] ").append(uid).append(" -> ").append(name).append("\n");
					Thread.sleep(200);
				} catch (Exception e) {
					log.append("  获取UID ").append(uid).append(" 信息失败: ").append(e.getMessage()).append("\n");
				}
			}

			// 3. 扫描下载目录，重命名匹配的UP主文件夹
			int renamed = 0;
			File[] entries = downloadDir.listFiles();
			if (entries != null) {
				// 先处理顶层UP主文件夹
				for (File entry : entries) {
					if (!entry.isDirectory()) continue;
					String folderName = entry.getName();
					// 已经是UID_格式的跳过
					if (folderName.matches("^[0-9]+_.*")) continue;

					String uid = nameToUid.get(folderName);
					if (uid != null) {
						String newName = uid + "_" + folderName;
						File newFolder = new File(downloadDir, newName);
						if (entry.renameTo(newFolder)) {
							log.append("  重命名: ").append(folderName).append(" -> ").append(newName).append("\n");
							renamed++;
						}
					}
				}

				// 再处理收藏夹内的UP主子文件夹
				for (File entry : entries) {
					if (!entry.isDirectory()) continue;
					// 已经是UID_格式的跳过
					if (entry.getName().matches("^[0-9]+_.*")) continue;
					// 不是UP主文件夹（可能是收藏夹）
					if (!nameToUid.containsKey(entry.getName())) {
						File[] subs = entry.listFiles();
						if (subs != null) {
							for (File sub : subs) {
								if (!sub.isDirectory()) continue;
								String subUid = nameToUid.get(sub.getName());
								if (subUid != null) {
									File newSubFolder = new File(downloadDir, subUid + "_" + sub.getName());
									if (moveDir(sub, newSubFolder)) {
										log.append("  移动: ").append(entry.getName()).append("/").append(sub.getName())
											.append(" -> ").append(newSubFolder.getName()).append("\n");
										renamed++;
									}
								}
							}
						}
					}
				}
			}
			log.append("完成! 重命名/移动 ").append(renamed).append(" 个文件夹\n");

		} catch (Exception e) {
			log.append("迁移过程出错: ").append(e.getMessage()).append("\n");
			e.printStackTrace();
		}
		return log.toString();
	}

	private static boolean moveDir(File src, File dest) {
		if (!src.exists()) return false;
		if (dest.exists()) {
			// 目标已存在，逐个移动内容
			File[] files = src.listFiles();
			if (files != null) {
				dest.mkdirs();
				boolean allOk = true;
				for (File f : files) {
					File target = new File(dest, f.getName());
					if (!f.renameTo(target)) allOk = false;
				}
				// 删除空源目录
				String[] remaining = src.list();
				if (remaining != null && remaining.length == 0) {
					src.delete();
				}
				return allOk;
			}
			return false;
		}
		dest.getParentFile().mkdirs();
		return src.renameTo(dest);
	}
}
