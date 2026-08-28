import re

# 1. AudioPlayerScreen
with open('app/src/main/java/com/example/ui/screens/AudioPlayerScreen.kt', 'r') as f:
    text = f.read()

# Replace `val initialMediaItem = if (offlineFile ...` block
text = re.sub(r'val initialMediaItem = .*?MediaItem\.fromUri\(audioUrl\)', 'val initialMediaItem = MediaItem.fromUri(audioUrl)', text, flags=re.DOTALL)
# The `val mediaItem = ...` for LaunchedEffect
text = re.sub(r'val mediaItem = .*?MediaItem\.fromUri\(url\)', 'val mediaItem = MediaItem.fromUri(url)', text, flags=re.DOTALL)
with open('app/src/main/java/com/example/ui/screens/AudioPlayerScreen.kt', 'w') as f:
    f.write(text)

# 2. FileDetailScreen
with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'r') as f:
    text = f.read()

text = re.sub(r'val isDownloaded = false', '', text) # if I previously added it?
text = re.sub(r'val downloadProgress = null', '', text)

# Just add `val isDownloaded = false` and `val downloadProgress: Float? = null` at the beginning of the scaffold content
text = text.replace('val nonNullFile = file ?: return@Scaffold', 'val nonNullFile = file ?: return@Scaffold\n            val isDownloaded = false\n            val downloadProgress: Float? = null')
# Remove OfflineFileManager references
text = re.sub(r'OfflineFileManager\.[a-zA-Z]+\(.*?context.*?accountId.*?\)', '', text)
with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'w') as f:
    f.write(text)

# 3. FilesScreen
with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    text = f.read()

# I will just replace `downloadedFiles.contains(file.effectiveId)` with `false`
text = text.replace('downloadedFiles.contains(file.effectiveId)', 'false')
text = text.replace('val downloadedFiles = emptySet<String>()', '')
# Add dummy downloadedFiles if needed, or just replace `val isOffline = false`
# Let's see if downloadedFiles is used anywhere else
text = re.sub(r'val isOffline = .*?false', 'val isOffline = false', text)

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
    f.write(text)
