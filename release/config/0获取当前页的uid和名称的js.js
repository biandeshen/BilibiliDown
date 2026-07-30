// 第一步：动态加载JS ZIP库（CDN方式，无需本地部署）
function loadJSZip() {
    return new Promise((resolve, reject) => {
        if (window.JSZip) {
            resolve(window.JSZip);
            return;
        }
        const script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/jszip@3.10.1/dist/jszip.min.js';
        script.onload = () => resolve(window.JSZip);
        script.onerror = () => reject(new Error('JSZIP库加载失败'));
        document.head.appendChild(script);
    });
}

// 第二步：核心函数 - 提取用户信息并生成ZIP压缩包
async function generateConfigFilesZip() {
    try {
        // 加载JS ZIP库
        const JSZip = await loadJSZip();
        const zip = new JSZip();

        // 获取目标用户节点列表
        const userLinkNodes = document.querySelectorAll("#app > main > div.space-follow > div.follow-main > section > div.items  > div > div > div > a");
        if (userLinkNodes.length === 0) {
            alert('未找到任何用户节点，请检查选择器是否正确！');
            return;
        }

        // 配置文件模板
        const configTemplate = `[url:https://space.bilibili.com/{userId}/video/]
start.page = 1
stop.condition = page:10000
#stop.condition = _:downloaded
# 表示 只下载没有下载过的
download.condition = _!downloaded
# 表示无条件下载，根据情况注释或去掉注释 
#download.condition = _:_
# 表示不包含边界（停止时的那个BV）
stop.bv.bounds = exclude
# 表示在每下完一个收藏夹，就弹出一次提示
stop.alert = true`;

        // 遍历节点，将每个配置文件添加到ZIP包
        let validFileCount = 0;
        userLinkNodes.forEach((node, index) => {
            try {
                const nickname = node.getAttribute('title');
                const href = node.getAttribute('href');
                const idMatch = href.match(/space\.bilibili\.com\/(\d+)\?/);

                if (!nickname || !idMatch || !idMatch[1]) {
                    console.warn(`第${index+1}个用户节点信息不完整，跳过`);
                    return;
                }

                const userId = idMatch[1];
                const configContent = configTemplate.replace(/{userId}/g, userId);
                const fileName = `batchDownload.${nickname}.config`;

                // 将配置文件添加到ZIP包中
                zip.file(fileName, configContent, { encoding: 'utf-8' });
                validFileCount++;
            } catch (error) {
                console.warn(`处理第${index+1}个用户节点失败：`, error);
            }
        });

        if (validFileCount === 0) {
            alert('未生成任何有效配置文件！');
            return;
        }

        // 生成ZIP文件并下载
        zip.generateAsync({ type: 'blob' }).then((blob) => {
            // 创建下载链接
            const downloadLink = document.createElement('a');
            downloadLink.download = 'bilibili_config_files.zip'; // ZIP包名称
            downloadLink.href = URL.createObjectURL(blob);
            downloadLink.click();
            // 释放URL资源
            URL.revokeObjectURL(downloadLink.href);

            alert(`处理完成！共生成${validFileCount}个配置文件，已打包为bilibili_config_files.zip并下载`);
        });

    } catch (error) {
        console.error('生成ZIP包失败：', error);
        alert(`生成ZIP包失败：${error.message}`);
    }
}

// 执行函数，生成并下载ZIP压缩包
generateConfigFilesZip();