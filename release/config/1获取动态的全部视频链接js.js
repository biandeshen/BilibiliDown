/**
 * 动态加载 JSZip 库。
 * @returns {Promise<JSZip>} 返回一个解析为 JSZip 库的 Promise。
 */
function loadJSZip() {
    return new Promise((resolve, reject) => {
        if (window.JSZip) {
            console.log('JSZip 已存在，直接使用。');
            resolve(window.JSZip);
            return;
        }
        console.log('正在从CDN加载 JSZip 库...');
        const script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js';
        script.onload = () => {
            console.log('JSZip 库加载成功！');
            resolve(window.JSZip);
        };
        script.onerror = () => reject(new Error('JSZip 库加载失败，请检查网络连接。'));
        document.head.appendChild(script);
    });
}

/**
 * 核心函数：提取页面所有B站视频链接，生成配置文件，并打包成ZIP下载。
 */
async function createConfigZipAndDownload() {
    try {
        // 1. 加载 JSZip 库
        const JSZip = await loadJSZip();
        const zip = new JSZip();

        // 2. 提取所有B站视频链接
        const bvRegex = /bv([a-zA-Z0-9]{10})/gi;
        const linkSet = new Set();

        // 从 <a> 标签的 href 中查找
        document.querySelectorAll('a[href]').forEach(link => {
            const href = link.getAttribute('href');
            if (href) {
                const matches = href.matchAll(bvRegex);
                for (const match of matches) {
                    const fullUrl = `https://www.bilibili.com/video/BV${match[1].toUpperCase()}`;
                    linkSet.add(fullUrl);
                }
            }
        });

        // 从 <script> 标签的内容中查找
        document.querySelectorAll('script').forEach(script => {
            const content = script.textContent;
            if (content) {
                const matches = content.matchAll(bvRegex);
                for (const match of matches) {
                    const fullUrl = `https://www.bilibili.com/video/BV${match[1].toUpperCase()}`;
                    linkSet.add(fullUrl);
                }
            }
        });

        if (linkSet.size === 0) {
            alert('在当前页面没有找到任何B站视频链接 (BV号)。');
            return;
        }
        console.log(`成功找到 ${linkSet.size} 个唯一的视频链接。`);

        // 3. 生成配置文件内容
        const urlList = Array.from(linkSet).join(',');
        const configContent = `[url:${urlList}]
start.page = 1
# 表示遇到下载过的视频时,停止查询
stop.condition = _!downloaded
# 表示只要查询到，我就加入下载队列
download.condition = _:_`;

        // 4. 将配置文件添加到 ZIP 包
        zip.file("batchDownload.config", configContent, { encoding: 'utf-8' });

        // 5. 生成 ZIP 文件并下载 (采用更稳健的方式)
        console.log('正在生成 ZIP 压缩包...');
        const blob = await zip.generateAsync({ type: 'blob' });

        // 生成随机文件名
        const randomString = Math.random().toString(36).substring(2, 10);
        const fileName = `batchDownload.${randomString}.zip`;

        // 创建下载链接
        const downloadLink = document.createElement('a');
        downloadLink.download = fileName;
        downloadLink.href = URL.createObjectURL(blob);

        // 触发点击下载
        downloadLink.click();

        // 释放URL资源
        URL.revokeObjectURL(downloadLink.href);

        console.log(`ZIP 包 "${fileName}" 已生成并开始下载！`);
        alert(`操作成功！\n\n已为您生成并开始下载包含配置文件的ZIP压缩包。\n\n文件名: ${fileName}\n包含 ${linkSet.size} 个视频链接。`);

    } catch (error) {
        console.error('生成ZIP包失败：', error);
        alert(`生成ZIP包失败：${error.message}`);
    }
}

// --- 立即执行函数 ---
createConfigZipAndDownload();