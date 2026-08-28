import re

for filename in ['VideoPlayerScreen.kt', 'AudioPlayerScreen.kt']:
    path = f'app/src/main/java/com/example/ui/screens/{filename}'
    with open(path, 'r') as f:
        content = f.read()

    # Find `(context, accountId, fileId)` and everything up to `} else {` and replace it with `val initialUrl = ApiClient.getFileUrl(accountId, fileId, preview = true)\nval initialMediaItem = MediaItem.fromUri(initialUrl)`
    content = re.sub(r'\s*\([^\)]*\)\s*val initialMediaItem = if \(offlineFile \!= null.*?\).*?\} else \{.*?val initialUrl =.*?\n.*?MediaItem\.fromUri\(initialUrl\)\s*\}', 
                     '\n        val initialUrl = ApiClient.getFileUrl(accountId, fileId, preview = true)\n        val initialMediaItem = MediaItem.fromUri(initialUrl)', content, flags=re.DOTALL)

    content = re.sub(r'\s*\([^\)]*\)\s*val mediaItem = if \(offlineFile \!= null.*?\).*?\} else \{.*?val url =.*?\n.*?MediaItem\.fromUri\(url\)\s*\}', 
                     '\n        val url = ApiClient.getFileUrl(accountId, currentActiveFileId, preview = true)\n        val mediaItem = MediaItem.fromUri(url)', content, flags=re.DOTALL)

    # For Audio, preview is false usually
    content = re.sub(r'\s*\([^\)]*\)\s*val initialMediaItem = if \(offlineFile \!= null.*?\).*?\} else \{.*?val initialUrl =.*?\n.*?MediaItem\.fromUri\(initialUrl\)\s*\}', 
                     '\n        val initialUrl = ApiClient.getFileUrl(accountId, fileId, preview = false)\n        val initialMediaItem = MediaItem.fromUri(initialUrl)', content, flags=re.DOTALL)

    content = re.sub(r'\s*\([^\)]*\)\s*val mediaItem = if \(offlineFile \!= null.*?\).*?\} else \{.*?val url =.*?\n.*?MediaItem\.fromUri\(url\)\s*\}', 
                     '\n        val url = ApiClient.getFileUrl(accountId, currentActiveFileId, preview = false)\n        val mediaItem = MediaItem.fromUri(url)', content, flags=re.DOTALL)

    with open(path, 'w') as f:
        f.write(content)

