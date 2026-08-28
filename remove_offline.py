import re

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'r') as f:
    content = f.read()

# Remove OfflineFileManager import
content = content.replace("import com.example.api.OfflineFileManager\n", "")

# Remove `val downloadedFiles by OfflineFileManager.downloadedFiles.collectAsState()`
content = re.sub(r'\s*val downloadedFiles by OfflineFileManager\.downloadedFiles\.collectAsState\(\)', '', content)

# Remove `val isOffline = ...`
content = re.sub(r'\s*val isOffline = .*?OfflineFileManager\.isDownloaded.*?$', '', content, flags=re.MULTILINE)

# Replace `if (isOffline) Color(0xFF10B981) else TextSecondary` with `TextSecondary`
content = content.replace('if (isOffline) Color(0xFF10B981) else TextSecondary', 'TextSecondary')

# Replace `if (file.isFolderItem) "Folder" else if (isOffline) "${file.formattedSize} • ${file.effectiveType.uppercase()} • Offline" else "${file.formattedSize} • ${file.effectiveType.uppercase()}"`
# With just `if (file.isFolderItem) "Folder" else "${file.formattedSize} • ${file.effectiveType.uppercase()}"`
content = content.replace('if (file.isFolderItem) "Folder" else if (isOffline) "${file.formattedSize} • ${file.effectiveType.uppercase()} • Offline" else "${file.formattedSize} • ${file.effectiveType.uppercase()}"', 
                          'if (file.isFolderItem) "Folder" else "${file.formattedSize} • ${file.effectiveType.uppercase()}"')

content = content.replace('if (file.isFolderItem) "Folder" else if (isOffline) "${file.formattedSize} • Offline" else file.formattedSize',
                          'if (file.isFolderItem) "Folder" else file.formattedSize')

# Remove the offline icon block in FileRow:
# if (isOffline) { ... Icon(Check) ... }
content = re.sub(r'\s*if\s*\(isOffline\)\s*\{\s*Icon\(Icons\.Default\.CheckCircle.*?\)\s*\}', '', content, flags=re.DOTALL)
content = re.sub(r'\s*if\s*\(isOffline\)\s*\{\s*Box.*?\s*Icon\(Icons\.Default\.Check.*?\)\s*\}\s*\}', '', content, flags=re.DOTALL)

# Remove Dropdown menu items for Save/Remove offline
offline_menu_pattern = r'\s*if\s*\(isOffline\)\s*\{.*?Remove Offline Copy.*?\}.*?else\s*\{.*?Save for Offline.*?\}'
content = re.sub(offline_menu_pattern, '', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/screens/FilesScreen.kt', 'w') as f:
    f.write(content)
