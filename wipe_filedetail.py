import re

with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'\s*val downloadedFiles by OfflineFileManager\.downloadedFiles\.collectAsState\(\)', '', content)
content = re.sub(r'\s*val activeDownloads by OfflineFileManager\.activeDownloads\.collectAsState\(\)', '', content)
content = re.sub(r'\s*val isDownloaded = downloadedFiles\.contains.*?$', '', content, flags=re.MULTILINE)
content = re.sub(r'\s*val downloadProgress = activeDownloads\[nonNullFile\.effectiveId\]', '', content)

# I can just remove the whole Row that contains "Offline Available".
# It starts around `Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)...` and ends before `HorizontalDivider()`
content = re.sub(r'\s*Row\(\s*modifier = Modifier\s*\.fillMaxWidth\(\)\s*\.padding\(vertical = 16\.dp\).*?HorizontalDivider\(color = Color\(0xFF2C2C3E\)\)', '\n                        HorizontalDivider(color = Color(0xFF2C2C3E))', content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'w') as f:
    f.write(content)
