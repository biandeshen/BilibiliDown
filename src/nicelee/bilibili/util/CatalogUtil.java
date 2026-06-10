package nicelee.bilibili.util;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UP主视频目录缓存：记录每个UP主已知的BV号，避免全量扫描
 * 文件格式: config/catalog/{uid}.json
 */
public class CatalogUtil {

	private static final File catalogDir = new File(ResourcesUtil.baseDirectory(), "config/catalog");
	private static final Map<String, Set<String>> catalogs = new ConcurrentHashMap<>();

	static {
		catalogDir.mkdirs();
	}

	/** 加载某个UP主的目录 */
	public static Set<String> load(String uid) {
		return catalogs.computeIfAbsent(uid, k -> {
			Set<String> set = ConcurrentHashMap.newKeySet();
			File f = new File(catalogDir, uid + ".json");
			if (!f.exists()) return set;
			try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "utf-8"))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = r.readLine()) != null) sb.append(line);
				JSONArray arr = new JSONObject(sb.toString()).getJSONArray("bvids");
				for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
			} catch (Exception e) { /* corrupt, start fresh */ }
			return set;
		});
	}

	/** 批量添加 bvid 并保存 */
	public static void addAndSave(String uid, Collection<String> bvids) {
		Set<String> set = load(uid);
		if (set.addAll(bvids)) save(uid, set);
	}

	/** 添加单个 bvid 并保存 */
	public static void addAndSave(String uid, String bvid) {
		Set<String> set = load(uid);
		if (set.add(bvid)) save(uid, set);
	}

	private static void save(String uid, Set<String> set) {
		File f = new File(catalogDir, uid + ".json");
		try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "utf-8"))) {
			JSONArray arr = new JSONArray();
			for (String s : set) arr.put(s);
			JSONObject obj = new JSONObject();
			obj.put("bvids", arr);
			obj.put("updated", System.currentTimeMillis());
			w.write(obj.toString());
		} catch (IOException e) { e.printStackTrace(); }
	}

	/** 检查 bvid 是否在目录中 */
	public static boolean contains(String uid, String bvid) {
		return load(uid).contains(bvid);
	}

	/** 某个UP主目录是否为空（未扫描过） */
	public static boolean isEmpty(String uid) {
		return load(uid).isEmpty();
	}
}
