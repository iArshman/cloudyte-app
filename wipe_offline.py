import os
import re

files_with_offline = [
    'app/src/main/java/com/example/ui/screens/VideoPlayerScreen.kt',
    'app/src/main/java/com/example/ui/screens/TextEditorScreen.kt',
    'app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt',
    'app/src/main/java/com/example/ui/screens/FileDetailScreen.kt',
    'app/src/main/java/com/example/ui/screens/AudioPlayerScreen.kt',
    'app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt',
    'app/src/main/java/com/example/ui/screens/SettingsScreen.kt',
    'app/src/main/java/com/example/MainActivity.kt'
]

for fp in files_with_offline:
    with open(fp, 'r') as f:
        content = f.read()

    # Remove import
    content = content.replace('import com.example.api.OfflineFileManager\n', '')
    
    # Remove autoCacheOnView
    content = re.sub(r'\s*OfflineFileManager\.autoCacheOnView\(.*?\)', '', content)
    
    # Remove findOfflineFile block and just use fileUrl
    # E.g. val offlineFile = remember(...) { OfflineFileManager.findOfflineFile(...) }
    # Then it uses offlineFile to build videoUrl/imageModel
    
    if 'ImageViewerScreen.kt' in fp:
        content = re.sub(r'val offlineFile = remember.*?\{.*?OfflineFileManager\.findOfflineFile.*?\}.*?val imageModel = remember\(offlineFile, fileUrl\) \{.*?if \(offlineFile \!= null && offlineFile\.exists\(\) && offlineFile\.length\(\) > 0\) \{.*?offlineFile.*?\} else \{.*?fileUrl.*?\}', 'val imageModel = fileUrl', content, flags=re.DOTALL)
    
    if 'VideoPlayerScreen.kt' in fp or 'AudioPlayerScreen.kt' in fp or 'PdfViewerScreen.kt' in fp or 'TextEditorScreen.kt' in fp or 'FileDetailScreen.kt' in fp:
        # Generic offline removal:
        content = re.sub(r'val offlineFile = remember.*?\{.*?OfflineFileManager\.findOfflineFile.*?\}.*?val \w+ = remember\(offlineFile, fileUrl\) \{.*?if \(offlineFile \!= null.*?\).*?Uri\.fromFile\(offlineFile\).*?else.*?Uri\.parse\(fileUrl\).*?\}', 'val mediaUri = android.net.Uri.parse(fileUrl)', content, flags=re.DOTALL)
        content = re.sub(r'val offlineFile = remember.*?\{.*?OfflineFileManager\.findOfflineFile.*?\}.*?val initialMediaItem = if \(offlineFile \!= null.*?\).*?MediaItem\.fromUri\(Uri\.fromFile\(offlineFile\)\).*?else.*?MediaItem\.fromUri\(Uri\.parse\(fileUrl\)\)', 'val initialMediaItem = androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(fileUrl))', content, flags=re.DOTALL)
        # for Video/Audio/Pdf
        content = re.sub(r'val offlineFile = .*?OfflineFileManager\.findOfflineFile.*?', '', content)

    # In SettingsScreen: clearAllOfflineCache etc.
    if 'SettingsScreen.kt' in fp:
        content = re.sub(r'var cacheSize by remember \{ mutableStateOf\(.*?getOfflineCacheSize.*?\)\}', 'var cacheSize by remember { androidx.compose.runtime.mutableStateOf(0L) }', content)
        content = re.sub(r'OfflineFileManager\.clearAllOfflineCache\(context\)', '', content)

    # In MainActivity
    if 'MainActivity.kt' in fp:
        content = content.replace('OfflineFileManager.init(this)', '')

    with open(fp, 'w') as f:
        f.write(content)

