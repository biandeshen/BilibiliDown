package nicelee.bilibili.util;

import org.slf4j.LoggerFactory;

/**
 * 日志工具类，桥接到slf4j。
 * 保持原有API兼容（print/println/printf），内部委托给slf4j。
 * 新代码请直接使用 org.slf4j.Logger。
 */
public class Logger {

	private static final org.slf4j.Logger log = LoggerFactory.getLogger(Logger.class);

	@Deprecated
	final static boolean mute;
	static {
		mute = !"true".equals(System.getProperty("bilibili.prop.log", "true"));
	}

	public static void print(Object str) {
		if (mute)
			return;
		log.info("{}", str);
	}

	public static void println() {
		if (mute)
			return;
		log.info("");
	}

	public static void printf(String str, Object... obj) {
		if (mute)
			return;
		StackTraceElement ele = Thread.currentThread().getStackTrace()[2];
		String file = ele.getFileName();
		file = file.substring(0, file.length() - 5);
		String method = ele.getMethodName();
		int line = ele.getLineNumber();
		String preStr = String.format(str, obj);
		String result = String.format("%s-%s/%d : %s", file, method, line, preStr);
		log.info(result);
	}

	public static void println(String str) {
		if (mute)
			return;
		StackTraceElement ele = Thread.currentThread().getStackTrace()[2];
		String file = ele.getFileName();
		file = file.substring(0, file.length() - 5);
		String method = ele.getMethodName();
		int line = ele.getLineNumber();
		String result = String.format("%s-%s/%d : %s", file, method, line, str);
		log.info(result);
	}

	public static void println(Object obj) {
		if (mute)
			return;
		StackTraceElement ele = Thread.currentThread().getStackTrace()[2];
		String file = ele.getFileName();
		file = file.substring(0, file.length() - 5);
		String method = ele.getMethodName();
		int line = ele.getLineNumber();
		String result = String.format("%s-%s/%d : %s", file, method, line, obj.toString());
		log.info(result);
	}
}
