// 工具函数
const getById = (id) => {
    return document.getElementById(id)
}

const createTag = (e) => {
    return document.createElement(e)
}

const getFileUrl = async (filePath) => {
    const port = await preload.port()
    return `http://localhost:${port}/${filePath.replaceAll('\\', '/')}`
}

const getUrlsByPaths = async (paths) => {
    let filePromises = paths.map(async p => await getFileUrl(p));
    return await Promise.all(filePromises)
}

