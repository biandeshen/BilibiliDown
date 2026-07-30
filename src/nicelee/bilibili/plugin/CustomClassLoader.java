package nicelee.bilibili.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CustomClassLoader extends ClassLoader {

	private static final Logger logger = LoggerFactory.getLogger(CustomClassLoader.class);

	private HashMap<String, Class<?>> classList = new HashMap<>();

	public CustomClassLoader() {
		super(CustomClassLoader.class.getClassLoader());
	}

	protected Class<?> findClass(String classPath, String className) {
		if (classList.containsKey(className))
			return classList.get(className);
		try {
			FileInputStream in = new FileInputStream(new File(classPath));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1024];
			for (int len = 0; (len = in.read(buffer)) != -1;) {
				out.write(buffer, 0, len);
			}
			in.close();
			byte[] bytes = out.toByteArray();
			Class<?> clazz = this.defineClass(className, bytes, 0, bytes.length);
			classList.put(className, clazz);
			return clazz;
		} catch (Exception e) {
			logger.error("异常", e);
			return null;
		}
	}

	protected Class<?> findClass(String className) {
		return classList.get(className);
	}
}
