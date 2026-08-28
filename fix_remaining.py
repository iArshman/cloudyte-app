import re

# PdfViewerScreen.kt
with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'r') as f:
    content = f.read()

# Replace offline file logic with direct URL use
content = re.sub(r'val offlineFile = .*?OfflineFileManager\.findOfflineFile.*?\n\s*if \(offlineFile \!= null && offlineFile\.exists\(\) && offlineFile\.length\(\) > 0\) \{\n\s*offlineFile\n\s*\} else \{\n\s*null\n\s*\}', 'val offlineFile: java.io.File? = null', content, flags=re.DOTALL)
# Actually, the original code had:
# val pdfUrl = remember(...) { ApiClient.getFileUrl(...) }
# val offlineFile = remember(...) { OfflineFileManager.findOfflineFile(...) }
# val fileToRender = remember(offlineFile, pdfUrl) { if (offlineFile... ) offlineFile else null }
# Wait, PDF viewer in Compose typically renders from an InputStream or URL using a webview or pdf renderer.
# Let's just fix the syntax error!
content = re.sub(r'val offlineFile = remember.*?\{.*?\}', 'val offlineFile: java.io.File? = null', content, flags=re.DOTALL)
content = re.sub(r'OfflineFileManager\.findOfflineFile.*?$', 'null', content, flags=re.MULTILINE)
with open('app/src/main/java/com/example/ui/screens/PdfViewerScreen.kt', 'w') as f:
    f.write(content)

# TextEditorScreen.kt
with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'r') as f:
    content = f.read()

content = re.sub(r'val offlineFile = remember.*?\{.*?\}', 'val offlineFile: java.io.File? = null', content, flags=re.DOTALL)
content = re.sub(r'OfflineFileManager\.findOfflineFile.*?$', 'null', content, flags=re.MULTILINE)
content = re.sub(r'val mediaUri = .*?Uri\.parse\(fileUrl\)', '', content) # it might not be there
content = re.sub(r'if \(offlineFile \!= null && offlineFile\.exists\(\) && offlineFile\.length\(\) > 0\)', 'if (false)', content)

with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'w') as f:
    f.write(content)

